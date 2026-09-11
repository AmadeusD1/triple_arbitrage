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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BinanceOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceOrderClient.class);
    private static final String BASE_URL = "https://api.binance.com";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // [priceDecimals, qtyDecimals] per symbol (e.g. "BTCUSDT"), refreshed from
    // /api/v3/exchangeInfo by warmPrecision().
    private final Map<String, int[]> precision = new ConcurrentHashMap<>();

    public BinanceOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BINANCE; }

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

    /**
     * Fetches {price, qty} decimal precision for each symbol from Binance's public exchangeInfo
     * endpoint (PRICE_FILTER.tickSize / LOT_SIZE.stepSize) and updates {@link #precision}.
     * Failures are logged and leave existing entries in place.
     */
    @Override
    public void warmPrecision(List<String> pairs) {
        if (pairs.isEmpty()) return;
        var symbolsJson = "[" + pairs.stream().map(p -> "\"" + p.toUpperCase() + "\"")
            .reduce((a, b) -> a + "," + b).orElse("") + "]";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var resp = mapper.readTree(http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/v3/exchangeInfo?symbols="
                            + URLEncoder.encode(symbolsJson, StandardCharsets.UTF_8)))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());

                var bySymbol = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                resp.path("symbols").forEach(s -> bySymbol.put(s.path("symbol").asText(), s));

                for (var pair : pairs) {
                    var symbol = pair.toUpperCase();
                    var node = bySymbol.get(symbol);
                    if (node == null) {
                        log.warn("[BINANCE] warmPrecision: no symbol info for {}", symbol);
                        continue;
                    }
                    var tickSize = "0.00000001";
                    var stepSize = "0.000001";
                    for (var filter : node.path("filters")) {
                        var type = filter.path("filterType").asText();
                        if ("PRICE_FILTER".equals(type)) tickSize = filter.path("tickSize").asText(tickSize);
                        else if ("LOT_SIZE".equals(type)) stepSize = filter.path("stepSize").asText(stepSize);
                    }
                    var prec = new int[]{decimalsOf(tickSize), decimalsOf(stepSize)};
                    precision.put(symbol, prec);
                    log.info("[BINANCE] warmPrecision: {} -> price={} qty={} decimals", symbol, prec[0], prec[1]);
                }
                return;
            } catch (Exception e) {
                log.warn("[BINANCE] warmPrecision attempt {}/3: {} {}", attempt, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 3) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
            }
        }
        log.error("[BINANCE] warmPrecision failed after 3 attempts — using fallback {8,6} decimals");
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
            var ts  = String.valueOf(System.currentTimeMillis());
            var body = "symbol=" + pair +
                       "&side=" + side.toUpperCase() +
                       "&type=" + orderType +
                       (isMarket
                           ? "&quantity=" + String.format("%." + prec[1] + "f", sentQty)
                           : "&timeInForce=GTC&quantity=" + String.format("%." + prec[1] + "f", sentQty)
                             + "&price=" + String.format("%." + prec[0] + "f", sentPrice)) +
                       "&timestamp=" + ts;
            var sig  = hmac256(apiSecret(), body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v3/order"))
                .header("X-MBX-APIKEY", apiKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body + "&signature=" + sig))
                .build();
            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("orderId")) return new OrderSendResult(node.path("orderId").asText(), null, sentPrice, sentQty);
            var msg = node.path("msg").asText("Order rejected");
            log.warn("[BINANCE] sendOrder rejected: {}", node);
            var lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("precision") || lowerMsg.contains("lot size") || lowerMsg.contains("decimal")) {
                reportError("Binance rejected an order due to a price/quantity precision error ("
                    + msg + ") on " + pair
                    + ". Trading on Binance has been stopped. Check the decimal precision "
                    + "configured for this pair in BinanceOrderClient and correct it, then restart "
                    + "Binance from Exchange Settings.");
            }
            return new OrderSendResult(null, msg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[BINANCE] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var ts  = String.valueOf(System.currentTimeMillis());
            var qs  = "symbol=" + pair + "&orderId=" + txid + "&timestamp=" + ts;
            var sig = hmac256(apiSecret(), qs);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v3/order?" + qs + "&signature=" + sig))
                .header("X-MBX-APIKEY", apiKey())
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();
            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("orderId")) return new CancelResult(true, null);
            return new CancelResult(false, node.path("msg").asText("Cancel rejected"));
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
