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

@Component
public class CoinbaseOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseOrderClient.class);
    private static final String BASE_URL = "https://api.exchange.coinbase.com";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    // [priceDecimals, qtyDecimals] per internal pair (e.g. "BTCUSD"), refreshed from
    // /products by warmPrecision().
    private final Map<String, int[]> precision = new ConcurrentHashMap<>();

    public CoinbaseOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.COINBASE; }

    @Override
    public int[] getPrecision(String pair) {
        var key = pair.toUpperCase();
        var prec = precision.get(key);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(key);
        }
        return prec != null ? prec : new int[]{8, 6};
    }

    /** BTCUSD → BTC-USD */
    private static String toProductId(String pair) {
        return pair.length() >= 4
            ? pair.substring(0, pair.length() - 3) + "-" + pair.substring(pair.length() - 3)
            : pair;
    }

    /**
     * Fetches {quote_increment (price), base_increment (qty)} for each product from Coinbase
     * Exchange's public /products endpoint and updates {@link #precision}. Failures are logged
     * and leave existing entries in place.
     */
    @Override
    public void warmPrecision(List<String> pairs) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var resp = mapper.readTree(http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/products"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());

                var byId = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                resp.forEach(p -> byId.put(p.path("id").asText(), p));

                for (var pair : pairs) {
                    var productId = toProductId(pair.toUpperCase());
                    var node = byId.get(productId);
                    if (node == null) {
                        log.warn("[COINBASE] warmPrecision: no product info for {} ({})", pair, productId);
                        continue;
                    }
                    var pd = decimalsOf(node.path("quote_increment").asText("0.01"));
                    var qd = decimalsOf(node.path("base_increment").asText("0.000001"));
                    precision.put(pair.toUpperCase(), new int[]{pd, qd});
                    log.info("[COINBASE] warmPrecision: {} ({}) -> price={} qty={} decimals", pair, productId, pd, qd);
                }
                return;
            } catch (Exception e) {
                log.warn("[COINBASE] warmPrecision attempt {}/3: {} {}", attempt, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 3) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
            }
        }
        log.error("[COINBASE] warmPrecision failed after 3 attempts — using fallback {8,6} decimals");
    }

    /** Number of digits after the decimal point in a numeric string like "0.0001" (4) or "1" (0), trailing zeros trimmed. */
    private static int decimalsOf(String numeric) {
        var s = numeric.trim();
        var dot = s.indexOf('.');
        if (dot < 0) return 0;
        var decimals = s.length() - dot - 1;
        while (decimals > 0 && s.charAt(s.length() - 1) == '0') { s = s.substring(0, s.length() - 1); decimals--; }
        return decimals;
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
        var prec = precision.getOrDefault(pair.toUpperCase(), new int[]{8, 6});
        var isMarket = "MARKET".equals(orderType);
        var sentPrice = isMarket ? price : round(price, prec[0]);
        var sentQty   = round(qty, prec[1]);
        try {
            var productId = toProductId(pair);
            var bodyNode = mapper.createObjectNode()
                .put("type", isMarket ? "market" : "limit")
                .put("side", side)
                .put("product_id", productId)
                .put("size", String.format("%." + prec[1] + "f", sentQty));
            if (!isMarket) {
                bodyNode.put("price", String.format("%." + prec[0] + "f", sentPrice));
                bodyNode.put("time_in_force", "GTC");
            }
            var bodyStr = mapper.writeValueAsString(bodyNode);
            var ts      = String.valueOf(System.currentTimeMillis() / 1000L);
            var prehash = ts + "POST" + "/orders" + bodyStr;
            var sig     = hmacBase64(apiSecret(), prehash);

            var cfg = configRepo.findByExchange("COINBASE").orElse(null);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/orders"))
                .header("CB-ACCESS-KEY", apiKey())
                .header("CB-ACCESS-SIGN", sig)
                .header("CB-ACCESS-TIMESTAMP", ts)
                .header("CB-ACCESS-PASSPHRASE", cfg != null && cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("id")) return new OrderSendResult(node.path("id").asText(), null, sentPrice, sentQty);
            var msg = node.path("message").asText("Order rejected");
            log.warn("[COINBASE] sendOrder rejected: {}", node);
            var lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("precision") || lowerMsg.contains("increment") || lowerMsg.contains("decimal")) {
                reportError("Coinbase rejected an order due to a price/size precision error ("
                    + msg + ") on " + productId
                    + ". Trading on Coinbase has been stopped. Check the decimal precision "
                    + "configured for this pair in CoinbaseOrderClient and correct it, then restart "
                    + "Coinbase from Exchange Settings.");
            }
            return new OrderSendResult(null, msg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[COINBASE] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var path    = "/orders/" + txid;
            var ts      = String.valueOf(System.currentTimeMillis() / 1000L);
            var prehash = ts + "DELETE" + path;
            var sig     = hmacBase64(apiSecret(), prehash);

            var cfg = configRepo.findByExchange("COINBASE").orElse(null);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("CB-ACCESS-KEY", apiKey())
                .header("CB-ACCESS-SIGN", sig)
                .header("CB-ACCESS-TIMESTAMP", ts)
                .header("CB-ACCESS-PASSPHRASE", cfg != null && cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "")
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("message")) return new CancelResult(false, node.path("message").asText());
            return new CancelResult(true, null);
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        var secretBytes = Base64.getDecoder().decode(secret);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
