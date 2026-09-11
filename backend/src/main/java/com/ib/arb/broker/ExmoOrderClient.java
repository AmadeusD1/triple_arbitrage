package com.ib.arb.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * EXMO order client.
 * Auth: Key = apiKey header, Sign = hex(HMAC-SHA512(urlEncodedPostBody, apiSecret)) header.
 * Order endpoint: POST /v1.1/order_create (pair, quantity, price, type=buy|sell).
 * Precision: GET /v1.1/pair_settings -> price_precision, min_quantity.
 */
@Component
public class ExmoOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(ExmoOrderClient.class);
    private static final String BASE_URL = "https://api.exmo.com/v1.1";

    // Longest-first so "USDT" matches before "USD", "USDC" before "USD", etc.
    private static final List<String> QUOTE_SUFFIXES = List.of(
        "USDT", "USDC", "EUR", "GBP", "TRY", "USD", "BTC", "ETH"
    );

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nonce = new AtomicLong(System.currentTimeMillis());

    // [priceDecimals, qtyDecimals] — populated by warmPrecision(); conservative fallback used until then
    private final ConcurrentHashMap<String, int[]> precision = new ConcurrentHashMap<>();

    public ExmoOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.EXMO; }

    @Override
    public int[] getPrecision(String pair) {
        var key = pair.toUpperCase();
        var prec = precision.get(key);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(key);
        }
        return prec != null ? prec : new int[]{8, 4};
    }

    @Override
    public void warmPrecision(List<String> pairs) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var resp = mapper.readTree(http.send(
                    HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/pair_settings")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());

                for (var pair : pairs) {
                    var node = resp.path(toExmoSymbol(pair));
                    if (node.isMissingNode()) {
                        log.warn("[EXMO] warmPrecision: no pair_settings for {}", pair);
                        continue;
                    }
                    var priceDecimals = node.path("price_precision").asInt(8);
                    var qtyDecimals    = decimalsOf(node.path("min_quantity").asText("0.0001"));
                    precision.put(pair.toUpperCase(), new int[]{priceDecimals, qtyDecimals});
                    log.info("[EXMO] warmPrecision: {} -> price={} qty={} decimals", pair, priceDecimals, qtyDecimals);
                }
                return;
            } catch (Exception e) {
                log.warn("[EXMO] warmPrecision attempt {}/3: {} {}", attempt, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 3) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
            }
        }
        log.error("[EXMO] warmPrecision failed after 3 attempts — using seeded defaults");
    }

    private record OrderSendResult(String orderId, String rejectionReason) {}

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var result = sendOrder(l.pair(), l.direction().toLowerCase(), l.orderType(), l.price(), l.quantity());
                    return new LegResult(l.legIndex(), l.pair(), l.direction(),
                        l.price(), l.quantity(), result.orderId() != null, result.orderId(), result.rejectionReason());
                }))
                .toList();
            return futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(LegResult::legIndex)).toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }

    private OrderSendResult sendOrder(String pair, String side, String orderType, double price, double qty) {
        try {
            var prec = precision.getOrDefault(pair.toUpperCase(), new int[]{8, 4});
            var isMarket = "MARKET".equals(orderType);
            var params = new LinkedHashMap<String, String>();
            params.put("nonce", String.valueOf(nonce.incrementAndGet()));
            params.put("pair", toExmoSymbol(pair));
            params.put("quantity", String.format("%." + prec[1] + "f", qty));
            if (!isMarket) params.put("price", String.format("%." + prec[0] + "f", price));
            params.put("type", isMarket ? "market_" + side : side);

            var body = encodeForm(params);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/order_create"))
                .header("Key",          apiKey())
                .header("Sign",         sign(body))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.path("result").asBoolean()) {
                return new OrderSendResult(String.valueOf(node.path("order_id").asLong()), null);
            }
            var error = node.path("error").asText("Order rejected");
            log.warn("[EXMO] sendOrder rejected: {}", node);
            var lower = error.toLowerCase();
            if (lower.contains("precision") || lower.contains("not a number") || lower.contains("incorrect")) {
                reportError("[EXMO] " + error);
            }
            return new OrderSendResult(null, error);
        } catch (Exception e) {
            log.error("[EXMO] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage());
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("nonce", String.valueOf(nonce.incrementAndGet()));
            params.put("order_id", txid);
            var body = encodeForm(params);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/order_cancel"))
                .header("Key",          apiKey())
                .header("Sign",         sign(body))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.path("result").asBoolean()) return new CancelResult(true, null);
            return new CancelResult(false, node.path("error").asText("Cancel rejected"));
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    /** hex(HMAC-SHA512(postData, apiSecret)) */
    private String sign(String postData) throws Exception {
        var mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(postData.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }

    /** Convert BTCUSDT -> BTC_USDT for EXMO */
    static String toExmoSymbol(String pair) {
        var norm = pair.toUpperCase();
        for (var q : QUOTE_SUFFIXES) {
            if (norm.endsWith(q) && norm.length() > q.length())
                return norm.substring(0, norm.length() - q.length()) + "_" + q;
        }
        return norm;
    }

    /** Counts significant decimal places in a numeric string like "0.0001" -> 4. */
    private static int decimalsOf(String numStr) {
        var idx = numStr.indexOf('.');
        if (idx < 0) return 0;
        var fraction = numStr.substring(idx + 1).replaceAll("0+$", "");
        return fraction.length();
    }
}
