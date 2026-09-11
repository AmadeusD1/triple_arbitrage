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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Component
public class BybitOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BybitOrderClient.class);
    private static final String BASE_URL    = "https://api.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final HttpClient   http   = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Bybit spot per-symbol {price, qty} decimal precision, seeded with known-good values and
     * refreshed from /v5/market/instruments-info by {@link #warmPrecision} whenever a triangle
     * referencing a new symbol is (re)activated.
     */
    private final Map<String, int[]> precision = new ConcurrentHashMap<>(Map.of(
        "BTCUSDT", new int[]{1, 6},
        "ETHUSDT", new int[]{2, 5},
        "ETHBTC",  new int[]{6, 5},
        "BTCUSDC", new int[]{1, 6},
        "ETHUSDC", new int[]{2, 5},
        "XRPUSDC", new int[]{4, 2},
        "XRPEUR",  new int[]{4, 2},
        "USDCEUR", new int[]{4, 2}
    ));

    public BybitOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BYBIT; }

    @Override
    public int[] getPrecision(String pair) {
        var symbol = pair.endsWith("USD") ? pair + "T" : pair;
        var prec = precision.get(symbol);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(symbol);
        }
        return prec != null ? prec : new int[]{8, 6};
    }

    private record OrderSendResult(String orderId, String rejectionReason, double sentPrice, double sentQty) {}

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var result = sendOrder(l.pair(), l.direction(), l.orderType(), l.price(), l.quantity());
                    return new LegResult(l.legIndex(), l.pair(), l.direction(),
                        result.sentPrice(), result.sentQty(), result.orderId() != null, result.orderId(), result.rejectionReason());
                }))
                .toList();
            return futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(LegResult::legIndex)).toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }

    private static double round(double v, int decimals) {
        return java.math.BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private OrderSendResult sendOrder(String pair, String direction, String orderType, double price, double qty) {
        // EURUSD → EURUSDT
        var symbol = pair.endsWith("USD") ? pair + "T" : pair;
        var prec   = precision.getOrDefault(symbol, new int[]{8, 6});
        var isMarket = "MARKET".equals(orderType);
        var sentPrice = isMarket ? price : round(price, prec[0]);
        var sentQty   = round(qty, prec[1]);
        try {
            var side   = direction.equalsIgnoreCase("BUY") ? "Buy" : "Sell";

            var bodyNode = mapper.createObjectNode()
                .put("category",    "spot")
                .put("symbol",      symbol)
                .put("side",        side)
                .put("orderType",   isMarket ? "Market" : "Limit")
                .put("qty",         String.format("%." + prec[1] + "f", sentQty));
            if (!isMarket) {
                bodyNode.put("price", String.format("%." + prec[0] + "f", sentPrice));
                bodyNode.put("timeInForce", "GTC");
            }
            var body = mapper.writeValueAsString(bodyNode);

            var ts   = String.valueOf(System.currentTimeMillis());
            var sign = sign(ts, apiKey(), RECV_WINDOW, body, apiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v5/order/create"))
                .header("Content-Type",       "application/json")
                .header("X-BAPI-API-KEY",     apiKey())
                .header("X-BAPI-SIGN",        sign)
                .header("X-BAPI-SIGN-TYPE",   "2")
                .header("X-BAPI-TIMESTAMP",   ts)
                .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (root.path("retCode").asInt() == 0) {
                return new OrderSendResult(root.path("result").path("orderId").asText(), null, sentPrice, sentQty);
            }
            var retCode = root.path("retCode").asInt();
            var retMsg  = root.path("retMsg").asText();
            log.error("[BYBIT] sendOrder failed: {} ({})", retMsg, retCode);
            var lowerMsg = retMsg.toLowerCase();
            if (lowerMsg.contains("precision") || lowerMsg.contains("decimal")) {
                reportError("Bybit rejected an order due to a price/quantity precision error ("
                    + retCode + ": " + retMsg + ") on " + symbol
                    + ". Trading on Bybit has been stopped. Check the decimal precision "
                    + "configured for this pair in BybitOrderClient and correct it, then restart "
                    + "Bybit from Exchange Settings.");
            }
            return new OrderSendResult(null, retCode + ": " + retMsg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[BYBIT] sendOrder exception: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            // EURUSD → EURUSDT, same reconstruction as sendOrder()/getPrecision()
            var symbol   = pair.endsWith("USD") ? pair + "T" : pair;
            var bodyNode = mapper.createObjectNode()
                .put("category", "spot")
                .put("symbol",   symbol)
                .put("orderId",  txid);
            var body = mapper.writeValueAsString(bodyNode);

            var ts   = String.valueOf(System.currentTimeMillis());
            var sign = sign(ts, apiKey(), RECV_WINDOW, body, apiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v5/order/cancel"))
                .header("Content-Type",       "application/json")
                .header("X-BAPI-API-KEY",     apiKey())
                .header("X-BAPI-SIGN",        sign)
                .header("X-BAPI-SIGN-TYPE",   "2")
                .header("X-BAPI-TIMESTAMP",   ts)
                .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (root.path("retCode").asInt() == 0) return new CancelResult(true, null);
            return new CancelResult(false, root.path("retCode").asInt() + ": " + root.path("retMsg").asText());
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private static String sign(String timestamp, String apiKey, String recvWindow,
                                String payload, String apiSecret) throws Exception {
        var raw = timestamp + apiKey + recvWindow + payload;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Fetches {price, qty} decimal precision for each pair's wire symbol from Bybit's public
     * instruments-info endpoint and updates {@link #precision}. Failures are logged and leave
     * the existing (seeded or previous) entry in place.
     *
     * <p>Called once at startup, when outbound HTTPS can intermittently fail for a few seconds
     * while the JVM's network stack settles — so each symbol is retried a few times before
     * giving up.
     */
    @Override
    public void warmPrecision(List<String> pairs) {
        for (var pair : pairs) {
            var symbol = pair.endsWith("USD") ? pair + "T" : pair;
            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    var request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/v5/market/instruments-info?category=spot&symbol=" + symbol))
                        .GET().build();
                    var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
                    if (root.path("retCode").asInt() != 0) {
                        log.warn("[BYBIT] warmPrecision: {} — {}", symbol, root.path("retMsg").asText());
                        lastError = null;
                        break;
                    }
                    var list = root.path("result").path("list");
                    if (!list.isArray() || list.isEmpty()) {
                        log.warn("[BYBIT] warmPrecision: no instrument info for {}", symbol);
                        lastError = null;
                        break;
                    }
                    var info = list.get(0);
                    var tickSize       = info.path("priceFilter").path("tickSize").asText();
                    var basePrecision  = info.path("lotSizeFilter").path("basePrecision").asText();
                    var prec = new int[]{decimalsOf(tickSize), decimalsOf(basePrecision)};
                    precision.put(symbol, prec);
                    log.info("[BYBIT] warmPrecision: {} -> price={} qty={} decimals", symbol, prec[0], prec[1]);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < 3) {
                        try { Thread.sleep(1000); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
            if (lastError != null) {
                log.warn("[BYBIT] warmPrecision failed for {} after 3 attempts: {}: {}",
                    symbol, lastError.getClass().getSimpleName(), lastError.getMessage());
            }
        }
    }

    /** Number of digits after the decimal point in a numeric string like "0.0001" (4) or "1" (0). */
    private static int decimalsOf(String numeric) {
        var dot = numeric.indexOf('.');
        return dot < 0 ? 0 : numeric.length() - dot - 1;
    }
}
