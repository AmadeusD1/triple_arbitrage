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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Component
public class HtxOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(HtxOrderClient.class);
    private static final String HOST     = "api.huobi.pro";
    private static final String BASE_URL = "https://" + HOST;
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    /**
     * HTX per-symbol {price, amount} decimal precision, seeded with known-good values and
     * refreshed from /v1/common/symbols by {@link #warmPrecision} whenever a triangle
     * referencing a new symbol is (re)activated.
     */
    private final Map<String, int[]> precision = new ConcurrentHashMap<>(Map.of(
        "btcusdt", new int[]{2, 6},
        "ethusdt", new int[]{2, 4},
        "ethbtc",  new int[]{6, 4}
    ));

    public HtxOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.HTX; }

    @Override
    public int[] getPrecision(String pair) {
        var symbol = pair.replace("/", "").toLowerCase();
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
        var isMarket = "MARKET".equals(orderType);
        if (isMarket && "buy".equals(side)) {
            // HTX market-buy `amount` means quote-currency spend, not base quantity —
            // unlike every other exchange/path in this app. Blocked rather than silently reinterpreted.
            return new OrderSendResult(null,
                "HTX market buy orders use quote-currency cost as the amount field, not base quantity. "
                + "This combination is not supported — use a LIMIT order instead.", price, qty);
        }

        var symbol = pair.replace("/", "").toLowerCase();
        var prec   = precision.getOrDefault(symbol, new int[]{8, 6});
        var sentPrice = isMarket ? price : round(price, prec[0]);
        var sentQty   = round(qty, prec[1]);
        try {
            var accountId = fetchSpotAccountId();
            if (accountId == null) return new OrderSendResult(null, "Could not resolve HTX spot account id", sentPrice, sentQty);

            var path    = "/v1/order/orders/place";
            var qs      = buildQueryString("POST", path);
            var bodyNode = mapper.createObjectNode()
                .put("account-id", accountId)
                .put("symbol",     symbol)
                .put("type",       side + (isMarket ? "-market" : "-limit"))
                .put("amount",     String.format("%." + prec[1] + "f", sentQty))
                .put("source",     "spot-api");
            if (!isMarket) bodyNode.put("price", String.format("%." + prec[0] + "f", sentPrice));
            var bodyStr = mapper.writeValueAsString(bodyNode);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + "?" + qs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if ("ok".equals(node.path("status").asText())) {
                return new OrderSendResult(node.path("data").asText(), null, sentPrice, sentQty);
            }
            var errCode = node.path("err-code").asText();
            var errMsg  = node.path("err-msg").asText();
            log.error("[HTX] sendOrder failed: {} — {}", errCode, errMsg);
            if (errCode.toLowerCase().contains("precision")) {
                reportError("HTX rejected an order due to a price/amount precision error ("
                    + errCode + ": " + errMsg + ") on " + symbol
                    + ". Trading on HTX has been stopped. Check the decimal precision "
                    + "configured for this pair in HtxOrderClient and correct it, then restart "
                    + "HTX from Exchange Settings.");
            }
            return new OrderSendResult(null, errCode + ": " + errMsg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[HTX] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var path    = "/v1/order/orders/" + txid + "/submitcancel";
            var qs      = buildQueryString("POST", path);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + "?" + qs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if ("ok".equals(node.path("status").asText())) return new CancelResult(true, null);
            return new CancelResult(false, node.path("err-code").asText() + ": " + node.path("err-msg").asText());
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private String fetchSpotAccountId() {
        try {
            var path = "/v1/account/accounts";
            var qs   = buildQueryString("GET", path);
            var req  = HttpRequest.newBuilder().uri(URI.create(BASE_URL + path + "?" + qs)).GET().build();
            var root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            for (var a : root.path("data")) {
                if ("spot".equals(a.path("type").asText()) && "working".equals(a.path("state").asText())) {
                    return a.path("id").asText();
                }
            }
        } catch (Exception e) {
            log.error("[HTX] fetchSpotAccountId failed: {}", e.getMessage());
        }
        return null;
    }

    private String buildQueryString(String method, String path) throws Exception {
        var ts  = FMT.format(ZonedDateTime.now(ZoneOffset.UTC));
        var sb  = new StringBuilder();
        sb.append("AccessKeyId=").append(enc(apiKey()));
        sb.append("&SignatureMethod=HmacSHA256");
        sb.append("&SignatureVersion=2");
        sb.append("&Timestamp=").append(enc(ts));

        var toSign = method + "\n" + HOST + "\n" + path + "\n" + sb;
        var sig    = hmac256(apiSecret(), toSign);
        sb.append("&Signature=").append(enc(sig));
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Fetches {price, amount} decimal precision for each pair's symbol from HTX's public
     * /v1/common/symbols endpoint and updates {@link #precision}. Failures are logged and leave
     * the existing (seeded or previous) entries in place.
     *
     * <p>Called once at startup, when outbound HTTPS can intermittently fail for a few seconds
     * while the JVM's network stack settles — so the call is retried a few times before
     * giving up.
     */
    @Override
    public void warmPrecision(List<String> pairs) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/common/symbols"))
                    .GET().build();
                var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
                if (!"ok".equals(root.path("status").asText())) {
                    log.warn("[HTX] warmPrecision: {} — {}", root.path("status").asText(), root.path("err-msg").asText());
                    return;
                }
                var bySymbol = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                for (var entry : root.path("data")) {
                    bySymbol.put(entry.path("symbol").asText(), entry);
                }
                for (var pair : pairs) {
                    var symbol = pair.replace("/", "").toLowerCase();
                    var entry = bySymbol.get(symbol);
                    if (entry == null) {
                        log.warn("[HTX] warmPrecision: no symbol info for {}", symbol);
                        continue;
                    }
                    var prec = new int[]{entry.path("price-precision").asInt(), entry.path("amount-precision").asInt()};
                    precision.put(symbol, prec);
                    log.info("[HTX] warmPrecision: {} -> price={} amount={} decimals", symbol, prec[0], prec[1]);
                }
                return;
            } catch (Exception e) {
                lastError = e;
                if (attempt < 3) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
        log.warn("[HTX] warmPrecision failed after 3 attempts: {}: {}",
            lastError.getClass().getSimpleName(), lastError.getMessage());
    }
}
