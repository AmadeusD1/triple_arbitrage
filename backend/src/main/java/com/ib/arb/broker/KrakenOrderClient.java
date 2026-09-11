package com.ib.arb.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.KrakenOrderBookFeed;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
import com.ib.arb.scanner.Signal;
import static com.ib.arb.common.Constants.Direction.BUY;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class KrakenOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenOrderClient.class);
    private static final String BASE_URL      = "https://api.kraken.com";
    private static final String ADD_ORDER_PATH = "/0/private/AddOrder";

    /** Fallback credentials read from application.yml (env vars) when no DB config exists. */
    @Value("${kraken.api-key:}")
    private String apiKeyFallback;

    @Value("${kraken.api-secret:}")
    private String apiSecretFallback;

    private final KrakenOrderBookFeed feed;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // [priceDecimals, volumeDecimals] per internal pair (e.g. "BTCUSD"), refreshed from
    // /0/public/AssetPairs by warmPrecision().
    private final Map<String, int[]> precision = new ConcurrentHashMap<>();

    private record LegMeta(int legIndex, String pair, String direction, double price, double volume) {}

    public KrakenOrderClient(KrakenOrderBookFeed feed, SettingRepository settings,
                             ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
        this.feed = feed;
    }

    @Override public Exchange getExchange() { return Exchange.KRAKEN; }

    @Override
    public int[] getPrecision(String pair) {
        var key = pair.toUpperCase();
        var prec = precision.get(key);
        if (prec == null) {
            warmPrecision(List.of(pair));
            prec = precision.get(key);
        }
        return prec != null ? prec : new int[]{5, 8};
    }

    /**
     * Fetches {price, volume} decimal precision for each pair from Kraken's public AssetPairs
     * endpoint and updates {@link #precision}. Kraken echoes the "BASE/QUOTE" query key back
     * verbatim as the response's top-level key, so no altname matching is needed.
     *
     * <p>Queried one pair at a time (not batched): Kraken fails the entire request with
     * {@code EQuery:Unknown asset pair} if even one symbol in a comma-separated batch is
     * invalid/unsupported, which would otherwise silently starve every other pair of real
     * precision. Each pair is retried a few times before falling back to {5,8} via
     * {@link #getPrecision}.
     */
    @Override
    public void warmPrecision(List<String> pairs) {
        for (var pair : pairs) {
            var krakenSymbol = KrakenOrderBookFeed.toKrakenSymbol(pair);
            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    var resp = mapper.readTree(http.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/0/public/AssetPairs?pair="
                                + URLEncoder.encode(krakenSymbol, StandardCharsets.UTF_8)))
                            .GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body());
                    var errors = resp.path("error");
                    if (!errors.isEmpty()) {
                        log.warn("[KRAKEN] warmPrecision: {} ({}) — {}", pair, krakenSymbol, errors);
                        lastError = null;
                        break;
                    }
                    var node = resp.path("result").path(krakenSymbol);
                    if (node.isMissingNode()) {
                        log.warn("[KRAKEN] warmPrecision: no pair info for {} ({})", pair, krakenSymbol);
                        lastError = null;
                        break;
                    }
                    var pd = node.path("pair_decimals").asInt(5);
                    var vd = node.path("lot_decimals").asInt(8);
                    precision.put(pair.toUpperCase(), new int[]{pd, vd});
                    log.info("[KRAKEN] warmPrecision: {} -> price={} volume={} decimals", pair, pd, vd);
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
                log.warn("[KRAKEN] warmPrecision failed for {} after 3 attempts: {}: {}",
                    pair, lastError.getClass().getSimpleName(), lastError.getMessage());
            }
        }
    }

    @Override
    public boolean isConnected() {
        return isSimulation() || (!effectiveApiKey().isBlank() && !effectiveApiSecret().isBlank());
    }

    /** DB config takes precedence over environment-variable fallbacks. */
    private String effectiveApiKey() {
        var key = apiKey();
        return key.isBlank() ? apiKeyFallback : key;
    }

    private String effectiveApiSecret() {
        var secret = apiSecret();
        return secret.isBlank() ? apiSecretFallback : secret;
    }

    /**
     * Computes per-leg order metadata from the live order book without placing orders.
     * Used by {@code AutoTrader} to build the legs list before simulation logging.
     */
    public List<LegResult> computeLegs(Signal signal, double orderSizeUsd) {
        return buildLegMeta(signal, orderSizeUsd).stream()
            .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(), l.price(), l.volume(), true, null, null))
            .toList();
    }

    private record OrderSendResult(String orderId, String rejectionReason, double sentPrice, double sentVolume) {}

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var krakenPair = KrakenOrderBookFeed.toKrakenSymbol(l.pair());
                    var result = sendOrder(l.pair(), krakenPair, l.direction().toLowerCase(), l.orderType(), l.price(), l.quantity());
                    return new LegResult(l.legIndex(), l.pair(), l.direction(),
                        result.sentPrice(), result.sentVolume(), result.orderId() != null, result.orderId(), result.rejectionReason());
                }))
                .toList();
            return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(LegResult::legIndex))
                .toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }

    private List<LegMeta> buildLegMeta(Signal signal, double orderSizeUsd) {
        var config     = signal.config();
        var pairs      = new String[]{ config.getPair1(), config.getPair2(), config.getPair3() };
        var directions = signal.cycle().dirs;

        var legs = new ArrayList<LegMeta>();
        for (int i = 0; i < 3; i++) {
            var snapshot = feed.getSnapshot(pairs[i]);
            if (snapshot == null) return List.of();
            var price = BUY.equals(directions[i]) ? snapshot.ask() : snapshot.bid();
            legs.add(new LegMeta(i + 1, pairs[i], directions[i], price, orderSizeUsd / price));
        }
        return legs;
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var nonce     = KrakenAuth.nextNonce();
            var postData  = "nonce=" + nonce + "&txid=" + txid;
            var signature = KrakenAuth.sign("/0/private/CancelOrder", nonce, postData, effectiveApiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/0/private/CancelOrder"))
                .header("API-Key",      effectiveApiKey())
                .header("API-Sign",     signature)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(postData))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var node     = mapper.readTree(response.body());
            var errors   = node.path("error");
            if (!errors.isEmpty()) {
                return new CancelResult(false, errors.toString());
            }
            return new CancelResult(true, null);
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private static double round(double v, int decimals) {
        return java.math.BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private OrderSendResult sendOrder(String internalPair, String pair, String type, String orderType, double price, double volume) {
        var prec = precision.getOrDefault(internalPair.toUpperCase(), new int[]{5, 8});
        var isMarket = "MARKET".equals(orderType);
        var sentPrice  = isMarket ? price : round(price, prec[0]);
        var sentVolume = round(volume, prec[1]);
        try {
            var nonce   = KrakenAuth.nextNonce();
            var params  = new LinkedHashMap<String, String>();
            params.put("nonce",     nonce);
            params.put("ordertype", isMarket ? "market" : "limit");
            params.put("type",      type);
            params.put("pair",      pair);
            if (!isMarket) params.put("price", String.format("%." + prec[0] + "f", sentPrice));
            params.put("volume",    String.format("%." + prec[1] + "f", sentVolume));

            var postData  = KrakenAuth.encodeForm(params);
            var signature = KrakenAuth.sign(ADD_ORDER_PATH, nonce, postData, effectiveApiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + ADD_ORDER_PATH))
                .header("API-Key",      effectiveApiKey())
                .header("API-Sign",     signature)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(postData))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var node = mapper.readTree(response.body());
            var errors = node.path("error");
            if (!errors.isEmpty()) {
                var errMsg = errors.toString();
                log.error("[KRAKEN] sendOrder failed: {}", errMsg);
                var lowerMsg = errMsg.toLowerCase();
                if (lowerMsg.contains("invalid price") || lowerMsg.contains("invalid volume")
                        || lowerMsg.contains("precision") || lowerMsg.contains("decimal")) {
                    reportError("Kraken rejected an order due to a price/volume precision error ("
                        + errMsg + ") on " + pair
                        + ". Trading on Kraken has been stopped. Check the decimal precision "
                        + "configured for this pair in KrakenOrderClient and correct it, then "
                        + "restart Kraken from Exchange Settings.");
                }
                return new OrderSendResult(null, errMsg, sentPrice, sentVolume);
            }
            var txid = node.path("result").path("txid").path(0).asText(null);
            return new OrderSendResult(txid, null, sentPrice, sentVolume);
        } catch (Exception e) {
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentVolume);
        }
    }
}
