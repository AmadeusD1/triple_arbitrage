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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BitstampOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BitstampOrderClient.class);
    private static final String BASE_URL = "https://www.bitstamp.net";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    // [priceDecimals, qtyDecimals] per internal pair (e.g. "BTCUSD"), refreshed from
    // /api/v2/markets/ by warmPrecision(). Note: the older /api/v2/trading-pairs-info/
    // endpoint is deprecated (unsupported since Dec 31 2024) — /markets/ replaces it.
    private final Map<String, int[]> precision = new ConcurrentHashMap<>();

    public BitstampOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BITSTAMP; }

    @Override
    public int[] getPrecision(String pair) {
        var key = pair.toUpperCase();
        var prec = precision.get(key);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(key);
        }
        return prec != null ? prec : new int[]{5, 6};
    }

    /**
     * Fetches {counter_decimals (price), base_decimals (qty)} for each pair from Bitstamp's
     * public /api/v2/markets/ endpoint and updates {@link #precision}. Failures are logged and
     * leave existing entries in place.
     */
    @Override
    public void warmPrecision(List<String> pairs) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var resp = mapper.readTree(http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v2/markets/"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());

                var bySymbol = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                resp.forEach(m -> bySymbol.put(m.path("market_symbol").asText(), m));

                for (var pair : pairs) {
                    var bsPair = pair.toLowerCase();
                    var node = bySymbol.get(bsPair);
                    if (node == null) {
                        log.warn("[BITSTAMP] warmPrecision: no market info for {}", bsPair);
                        continue;
                    }
                    var pd = node.path("counter_decimals").asInt(5);
                    var qd = node.path("base_decimals").asInt(6);
                    precision.put(pair.toUpperCase(), new int[]{pd, qd});
                    log.info("[BITSTAMP] warmPrecision: {} -> price={} qty={} decimals", pair, pd, qd);
                }
                return;
            } catch (Exception e) {
                log.warn("[BITSTAMP] warmPrecision attempt {}/3: {} {}", attempt, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 3) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
            }
        }
        log.error("[BITSTAMP] warmPrecision failed after 3 attempts — using fallback {5,6} decimals");
    }

    private static double round(double v, int decimals) {
        return java.math.BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private record OrderSendResult(String orderId, String rejectionReason, double sentPrice, double sentQty) {}

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var result = sendOrder(l.pair(), l.direction().toLowerCase(), l.orderType(), l.price(), l.quantity());
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

    private OrderSendResult sendOrder(String pair, String side, String orderType, double price, double qty) {
        var prec = precision.getOrDefault(pair.toUpperCase(), new int[]{5, 6});
        var isMarket = "MARKET".equals(orderType);
        var sentPrice = isMarket ? price : round(price, prec[0]);
        var sentQty   = round(qty, prec[1]);
        try {
            // Bitstamp pair: EURUSD → eurusd
            var bsPair      = pair.toLowerCase();
            var path        = "/api/v2/" + side + "/" + (isMarket ? "market" : "limit") + "/" + bsPair + "/";
            var body        = "amount=" + String.format("%." + prec[1] + "f", sentQty)
                + (isMarket ? "" : "&price=" + String.format("%." + prec[0] + "f", sentPrice));
            var nonce       = UUID.randomUUID().toString().replace("-", "");
            var ts          = String.valueOf(System.currentTimeMillis());
            var contentType = "application/x-www-form-urlencoded";
            var stringToSign = "BITSTAMP " + apiKey() +
                "\nPOST\nwww.bitstamp.net\n" + path + "\n" + contentType + "\n" + nonce + "\n" + ts + "\nv2\n" + body;
            var sig = hmac256(apiSecret(), stringToSign);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("X-Auth", "BITSTAMP " + apiKey())
                .header("X-Auth-Signature", sig)
                .header("X-Auth-Nonce", nonce)
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Version", "v2")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("id")) return new OrderSendResult(node.path("id").asText(), null, sentPrice, sentQty);
            var msg = node.has("reason") ? node.path("reason").toString() : node.path("status").asText("Order rejected");
            log.warn("[BITSTAMP] sendOrder rejected: {}", node);
            var lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("precision") || lowerMsg.contains("decimal")) {
                reportError("Bitstamp rejected an order due to a price/amount precision error ("
                    + msg + ") on " + bsPair
                    + ". Trading on Bitstamp has been stopped. Check the decimal precision "
                    + "configured for this pair in BitstampOrderClient and correct it, then restart "
                    + "Bitstamp from Exchange Settings.");
            }
            return new OrderSendResult(null, msg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[BITSTAMP] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var path        = "/api/v2/cancel_order/";
            var body        = "id=" + txid;
            var nonce       = UUID.randomUUID().toString().replace("-", "");
            var ts          = String.valueOf(System.currentTimeMillis());
            var contentType = "application/x-www-form-urlencoded";
            var stringToSign = "BITSTAMP " + apiKey() +
                "\nPOST\nwww.bitstamp.net\n" + path + "\n" + contentType + "\n" + nonce + "\n" + ts + "\nv2\n" + body;
            var sig = hmac256(apiSecret(), stringToSign);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("X-Auth", "BITSTAMP " + apiKey())
                .header("X-Auth-Signature", sig)
                .header("X-Auth-Nonce", nonce)
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Version", "v2")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("id")) return new CancelResult(true, null);
            var msg = node.has("reason") ? node.path("reason").toString() : node.path("status").asText("Cancel rejected");
            return new CancelResult(false, msg);
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }
}
