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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class KucoinOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(KucoinOrderClient.class);
    private static final String BASE_URL = "https://api.kucoin.com";

    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // [priceDecimals, qtyDecimals] per internal pair (e.g. "BTCUSDT")
    private final ConcurrentHashMap<String, int[]> precision = new ConcurrentHashMap<>(Map.of(
        "BTCUSDT",  new int[]{1, 8},
        "ETHUSDT",  new int[]{2, 7},
        "ETHBTC",   new int[]{5, 7},
        "XRPUSDT",  new int[]{5, 4},
        "XRPBTC",   new int[]{8, 4},
        "SOLUSDT",  new int[]{2, 3},
        "BTCUSDC",  new int[]{1, 8},
        "ETHUSDC",  new int[]{2, 7},
        "SOLUSDC",  new int[]{2, 3}
    ));

    public KucoinOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.KUCOIN; }

    @Override
    public int[] getPrecision(String pair) {
        var key = pair.toUpperCase();
        var prec = precision.get(key);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(key);
        }
        return prec != null ? prec : new int[]{2, 6};
    }

    @Override
    public void warmPrecision(List<String> pairs) {
        try {
            var resp = mapper.readTree(http.send(
                HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/v1/symbols")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());

            var bySymbol = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            resp.path("data").forEach(s -> bySymbol.put(s.path("symbol").asText(), s));

            for (var pair : pairs) {
                var kcSym = toKcSymbol(pair);
                var node  = bySymbol.get(kcSym);
                if (node == null) {
                    log.warn("[KUCOIN] warmPrecision: no symbol info for {} ({})", pair, kcSym);
                    continue;
                }
                var pd = decimalsOf(node.path("priceIncrement").asText("0.01"));
                var qd = decimalsOf(node.path("baseIncrement").asText("0.000001"));
                precision.put(pair.toUpperCase(), new int[]{pd, qd});
                log.info("[KUCOIN] warmPrecision: {} ({}) -> price={} qty={} decimals", pair, kcSym, pd, qd);
            }
        } catch (Exception e) {
            log.error("[KUCOIN] warmPrecision failed: {}", e.getMessage());
        }
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
        var prec     = precision.getOrDefault(pair.toUpperCase(), new int[]{2, 6});
        var isMarket = "MARKET".equals(orderType);
        var sentPrice = isMarket ? price : round(price, prec[0]);
        var sentQty   = round(qty, prec[1]);
        try {
            var kcSymbol = toKcSymbol(pair);

            var bodyNode = mapper.createObjectNode()
                .put("clientOid", UUID.randomUUID().toString())
                .put("side",      side)
                .put("symbol",    kcSymbol)
                .put("type",      isMarket ? "market" : "limit")
                .put("size",      String.format("%." + prec[1] + "f", sentQty));
            if (!isMarket) bodyNode.put("price", String.format("%." + prec[0] + "f", sentPrice));
            var bodyStr = mapper.writeValueAsString(bodyNode);
            var path    = "/api/v1/orders";
            var ts      = String.valueOf(System.currentTimeMillis());
            var prehash = ts + "POST" + path + bodyStr;
            var sig     = hmacBase64(apiSecret(), prehash);
            var pp      = hmacBase64(apiSecret(), apiPassphrase());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("KC-API-KEY",         apiKey())
                .header("KC-API-SIGN",        sig)
                .header("KC-API-TIMESTAMP",   ts)
                .header("KC-API-PASSPHRASE",  pp)
                .header("KC-API-KEY-VERSION", "2")
                .header("Content-Type",       "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("data")) return new OrderSendResult(node.path("data").path("orderId").asText(), null, sentPrice, sentQty);
            log.warn("[KUCOIN] sendOrder rejected: {}", node);
            var msg = node.path("msg").asText("Order rejected");
            return new OrderSendResult(null, msg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[KUCOIN] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var path    = "/api/v1/orders/" + txid;
            var ts      = String.valueOf(System.currentTimeMillis());
            var prehash = ts + "DELETE" + path;
            var sig     = hmacBase64(apiSecret(), prehash);
            var pp      = hmacBase64(apiSecret(), apiPassphrase());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("KC-API-KEY",         apiKey())
                .header("KC-API-SIGN",        sig)
                .header("KC-API-TIMESTAMP",   ts)
                .header("KC-API-PASSPHRASE",  pp)
                .header("KC-API-KEY-VERSION", "2")
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("data")) return new CancelResult(true, null);
            return new CancelResult(false, node.path("msg").asText("Cancel rejected"));
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    /** Convert internal pair (BTCUSDT) → KuCoin symbol (BTC-USDT) */
    private static String toKcSymbol(String pair) {
        if      (pair.endsWith("USDT") && pair.length() > 4) return pair.substring(0, pair.length() - 4) + "-USDT";
        else if (pair.endsWith("USDC") && pair.length() > 4) return pair.substring(0, pair.length() - 4) + "-USDC";
        else if (pair.endsWith("BTC")  && pair.length() > 3) return pair.substring(0, pair.length() - 3) + "-BTC";
        else if (pair.endsWith("ETH")  && pair.length() > 3) return pair.substring(0, pair.length() - 3) + "-ETH";
        return pair.length() > 3 ? pair.substring(0, pair.length() - 3) + "-" + pair.substring(pair.length() - 3) : pair;
    }

    /** "0.001" → 3, "0.1" → 1, "1" → 0 */
    private static int decimalsOf(String increment) {
        var s = increment.trim();
        var dot = s.indexOf('.');
        if (dot < 0) return 0;
        var decimals = s.length() - dot - 1;
        // trim trailing zeros: "0.010" → 2 but usually not seen from KuCoin
        while (decimals > 0 && s.charAt(s.length() - 1) == '0') { s = s.substring(0, s.length() - 1); decimals--; }
        return decimals;
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
