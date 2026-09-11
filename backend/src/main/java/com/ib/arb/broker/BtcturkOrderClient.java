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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BtcTurk order client.
 * Auth: X-PCK = apiKey, X-Stamp = millis, X-Signature = base64(HMAC-SHA256(apiKey+stamp, base64Decode(apiSecret)))
 * Order endpoint: POST /api/v1/newOrder
 * Precision: GET /api/v2/server/exchangeinfo → denominatorScale (price), numeratorScale (qty)
 */
@Component
public class BtcturkOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BtcturkOrderClient.class);
    private static final String BASE_URL = "https://api.btcturk.com";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // [priceDecimals, qtyDecimals] seeded from BtcTurk typical values
    private final ConcurrentHashMap<String, int[]> precision = new ConcurrentHashMap<>(Map.of(
        "BTCUSDT",  new int[]{2, 8},
        "BTCTRY",   new int[]{2, 8},
        "USDTTRY",  new int[]{4, 2},
        "ETHTRY",   new int[]{2, 8},
        "ETHUSDT",  new int[]{2, 8},
        "XRPUSDT",  new int[]{5, 2},
        "XRPTRY",   new int[]{4, 2}
    ));

    public BtcturkOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BTCTURK; }

    @Override
    public int[] getPrecision(String pair) {
        var key = pair.toUpperCase();
        var prec = precision.get(key);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(key);
        }
        return prec != null ? prec : new int[]{2, 8};
    }

    @Override
    public void warmPrecision(List<String> pairs) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var resp = mapper.readTree(http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v2/server/exchangeinfo"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());

                var bySymbol = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                resp.path("data").path("symbols").forEach(p -> bySymbol.put(p.path("name").asText(), p));

                for (var pair : pairs) {
                    var node = bySymbol.get(pair.toUpperCase());
                    if (node == null) {
                        log.warn("[BTCTURK] warmPrecision: no symbol info for {}", pair);
                        continue;
                    }
                    var pd = node.path("denominatorScale").asInt(2);
                    var qd = node.path("numeratorScale").asInt(8);
                    precision.put(pair.toUpperCase(), new int[]{pd, qd});
                    log.info("[BTCTURK] warmPrecision: {} -> price={} qty={} decimals", pair, pd, qd);
                }
                return;
            } catch (Exception e) {
                log.warn("[BTCTURK] warmPrecision attempt {}/3: {} {}", attempt, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 3) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
            }
        }
        log.error("[BTCTURK] warmPrecision failed after 3 attempts — using seeded defaults");
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

    private static double round(double v, int decimals) {
        return java.math.BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private OrderSendResult sendOrder(String pair, String side, String orderType, double price, double qty) {
        var prec = precision.getOrDefault(pair.toUpperCase(), new int[]{2, 8});
        var isMarket = "MARKET".equals(orderType);
        var sentPrice = isMarket ? price : round(price, prec[0]);
        var sentQty   = round(qty, prec[1]);
        try {
            var ts   = String.valueOf(System.currentTimeMillis());
            var fields = new java.util.LinkedHashMap<String, String>();
            fields.put("pairSymbol",  pair.toUpperCase());
            fields.put("orderType",   side);
            fields.put("orderMethod", isMarket ? "market" : "limit");
            if (!isMarket) fields.put("price", String.format("%." + prec[0] + "f", sentPrice));
            fields.put("quantity",    String.format("%." + prec[1] + "f", sentQty));
            var body = mapper.writeValueAsString(fields);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v1/order"))
                .header("X-PCK",        apiKey())
                .header("X-Stamp",      ts)
                .header("X-Signature",  sign(ts))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var responseBody = response.body();
            log.info("[BTCTURK] sendOrder HTTP {} body={}", response.statusCode(), responseBody);
            var node = mapper.readTree(responseBody);
            if (node.path("success").asBoolean()) {
                return new OrderSendResult(String.valueOf(node.path("data").path("id").asLong()), null, sentPrice, sentQty);
            }
            log.warn("[BTCTURK] sendOrder rejected (raw): {}", responseBody);
            var msgNode = node.path("message");
            var msg = (!msgNode.isMissingNode() && !msgNode.isNull() && !msgNode.asText().isBlank())
                ? msgNode.asText()
                : (node.has("code") ? "BtcTurk error code " + node.path("code").asInt() : "Order rejected");
            return new OrderSendResult(null, msg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[BTCTURK] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var ts = String.valueOf(System.currentTimeMillis());
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v1/order?id=" + txid))
                .header("X-PCK",       apiKey())
                .header("X-Stamp",     ts)
                .header("X-Signature", sign(ts))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();

            var responseBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            var node = mapper.readTree(responseBody);
            if (node.path("success").asBoolean()) return new CancelResult(true, null);
            log.warn("[BTCTURK] cancelOrder rejected (raw): {}", responseBody);
            var msgNode = node.path("message");
            var msg = (!msgNode.isMissingNode() && !msgNode.isNull() && !msgNode.asText().isBlank())
                ? msgNode.asText()
                : (node.has("code") ? "BtcTurk error code " + node.path("code").asInt() : "Cancel rejected");
            return new CancelResult(false, msg);
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    /** base64(HMAC-SHA256(apiKey + stamp, base64Decode(apiSecret))) */
    private String sign(String stamp) throws Exception {
        var decodedSecret = Base64.getDecoder().decode(apiSecret());
        var message = (apiKey() + stamp).getBytes(StandardCharsets.UTF_8);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(decodedSecret, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message));
    }
}
