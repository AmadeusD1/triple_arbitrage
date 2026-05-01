package com.ib.arb.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.KrakenOrderBookFeed;
import com.ib.arb.repository.SettingRepository;
import com.ib.arb.scanner.Signal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for Kraken order placement via the Kraken REST API.
 *
 * <p>Supports two operating modes controlled by the {@code simulation_mode} setting:
 * <ul>
 *   <li><b>Simulation</b> ({@code simulation_mode = 1}): {@link #computeLegs} returns
 *       the intended order metadata from the live order book without placing any orders.
 *       Used by {@code AutoTrader} for dry-run logging.</li>
 *   <li><b>Live</b> ({@code simulation_mode = 0}): {@link #placeComboOrder} places three
 *       sequential limit orders at the current bid/ask and returns each leg's Kraken txid.</li>
 * </ul>
 *
 * <p>Open order count is tracked locally with an {@link AtomicInteger} so the scheduler
 * can gate new cycles without an extra API call.
 *
 * <p>Authentication uses Kraken's HMAC-SHA512 scheme:
 * {@code Base64(HMAC-SHA512(Base64Decode(secret), path + SHA256(nonce + postData)))}.
 */
@Component
public class KrakenOrderClient {

    private static final String BASE_URL = "https://api.kraken.com";
    private static final String ADD_ORDER_PATH = "/0/private/AddOrder";

    @Value("${kraken.api-key:}")
    private String apiKey;

    @Value("${kraken.api-secret:}")
    private String apiSecret;

    private final KrakenOrderBookFeed feed;
    private final SettingRepository settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger openOrders = new AtomicInteger(0);

    /**
     * Per-leg result returned to {@code AutoTrader} after an order attempt.
     *
     * @param legIndex  position in the triangle (1, 2, or 3)
     * @param pair      FX pair code, e.g. {@code "EURUSD"}
     * @param direction {@code "BUY"} or {@code "SELL"}
     * @param price     limit price used (bid for sells, ask for buys)
     * @param volume    quantity in base currency units
     * @param filled    {@code true} if Kraken accepted the order
     * @param orderId   Kraken transaction ID ({@code txid}); {@code null} for simulation
     *                  legs and failed legs
     */
    public record LegResult(int legIndex, String pair, String direction,
                            double price, double volume, boolean filled, String orderId) {}

    /** Internal metadata computed from the order book before any orders are placed. */
    private record LegMeta(int legIndex, String pair, String direction, double price, double volume) {}

    public KrakenOrderClient(KrakenOrderBookFeed feed, SettingRepository settings) {
        this.feed = feed;
        this.settings = settings;
    }

    /**
     * Returns {@code true} if the client can accept orders.
     *
     * <p>In simulation mode this always returns {@code true} regardless of whether API
     * credentials are configured. In live mode it returns {@code true} only if both
     * {@code kraken.api-key} and {@code kraken.api-secret} are non-blank.
     */
    public boolean isConnected() {
        return isSimulation() || (!apiKey.isBlank() && !apiSecret.isBlank());
    }

    /**
     * Returns {@code true} if simulation mode is currently enabled.
     *
     * <p>Reads the {@code simulation_mode} setting from the database on every call.
     * Defaults to {@code true} (safe) if the setting is absent.
     */
    public boolean isSimulation() {
        return settings.findById("simulation_mode")
            .map(s -> s.getValue() == 1.0)
            .orElse(true);
    }

    /**
     * Returns the number of orders currently being placed by this client.
     *
     * <p>Incremented at the start of {@link #placeComboOrder} and decremented when it
     * returns, so a value of {@code 1} means a combo is in flight. Used by
     * {@code AutoTrader} to enforce the {@code max-open-orders} limit.
     */
    public int openOrderCount() {
        return openOrders.get();
    }

    /**
     * Computes per-leg order metadata from the current order book without placing any orders.
     *
     * <p>Reads the live bid/ask for each leg and calculates the volume as
     * {@code orderSizeUsd / price}. Called by {@code AutoTrader} in simulation mode
     * so the intended orders can be logged accurately.
     *
     * @param signal       the arbitrage signal identifying exchange, triangle, and cycle
     * @param orderSizeUsd notional order size in USD
     * @return list of three {@link LegResult}s with {@code filled=true} and {@code orderId=null};
     *         empty list if any snapshot is unavailable
     */
    public List<LegResult> computeLegs(Signal signal, double orderSizeUsd) {
        return buildLegMeta(signal, orderSizeUsd).stream()
            .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(), l.price(), l.volume(), true, null))
            .toList();
    }

    /**
     * Places three sequential limit orders on Kraken for the given triangle signal.
     *
     * <p>Order directions per cycle:
     * <ul>
     *   <li><b>Cycle A</b>: BUY pair1, BUY pair2, SELL pair3</li>
     *   <li><b>Cycle B</b>: SELL pair1, SELL pair2, BUY pair3</li>
     * </ul>
     *
     * <p>Execution stops at the first failed order. If leg 2 fails, leg 3 is not attempted
     * and the returned list will contain only 2 elements (leg 1 filled, leg 2 failed).
     * The caller is responsible for interpreting partial fills.
     *
     * @param signal       the arbitrage signal
     * @param orderSizeUsd notional size in USD; volume per leg = {@code orderSizeUsd / price}
     * @return list of {@link LegResult}s in leg order (1–3); may be shorter than 3 if a
     *         leg failed or a snapshot was unavailable; empty if no snapshots could be read
     */
    public List<LegResult> placeComboOrder(Signal signal, double orderSizeUsd) {
        var meta = buildLegMeta(signal, orderSizeUsd);
        if (meta.isEmpty()) return List.of();

        var results = new ArrayList<LegResult>();
        openOrders.incrementAndGet();
        try {
            for (var l : meta) {
                var krakenPair = KrakenOrderBookFeed.toKrakenSymbol(l.pair());
                var txid = addOrder(krakenPair, l.direction().toLowerCase(), l.price(), l.volume());
                results.add(new LegResult(l.legIndex(), l.pair(), l.direction(),
                    l.price(), l.volume(), txid.isPresent(), txid.orElse(null)));
                if (txid.isEmpty()) break;
            }
        } finally {
            openOrders.decrementAndGet();
        }
        return results;
    }

    /**
     * Builds leg metadata from the current order book for all three legs of the signal.
     * Returns an empty list if any snapshot is missing — the caller must treat this as
     * an unexecutable signal.
     */
    private List<LegMeta> buildLegMeta(Signal signal, double orderSizeUsd) {
        var config = signal.config();
        var cycleA = "A".equals(signal.cycle());
        var pairs = new String[]{ config.getPair1(), config.getPair2(), config.getPair3() };
        var directions = cycleA
            ? new String[]{ "BUY", "BUY", "SELL" }
            : new String[]{ "SELL", "SELL", "BUY" };

        var legs = new ArrayList<LegMeta>();
        for (int i = 0; i < 3; i++) {
            var snapshot = feed.getSnapshot(pairs[i]);
            if (snapshot == null) return List.of();
            var price = "BUY".equals(directions[i]) ? snapshot.ask() : snapshot.bid();
            legs.add(new LegMeta(i + 1, pairs[i], directions[i], price, orderSizeUsd / price));
        }
        return legs;
    }

    /** Submits one limit order to Kraken and returns the txid on success, or empty on any failure. */
    private Optional<String> addOrder(String pair, String type, double price, double volume) {
        try {
            var nonce = String.valueOf(System.currentTimeMillis());
            var params = new LinkedHashMap<String, String>();
            params.put("nonce", nonce);
            params.put("ordertype", "limit");
            params.put("type", type);
            params.put("pair", pair);
            params.put("price", String.format("%.5f", price));
            params.put("volume", String.format("%.5f", volume));

            var postData = encodeForm(params);
            var signature = sign(ADD_ORDER_PATH, nonce, postData);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + ADD_ORDER_PATH))
                .header("API-Key", apiKey)
                .header("API-Sign", signature)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(postData))
                .build();

            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var node = mapper.readTree(response.body());
            if (!node.path("error").isEmpty()) return Optional.empty();

            var txid = node.path("result").path("txid").path(0).asText(null);
            return Optional.ofNullable(txid);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // Kraken signature: Base64(HMAC-SHA512(Base64Decode(secret), path + SHA256(nonce + postData)))
    private String sign(String path, String nonce, String postData) throws Exception {
        var sha256 = MessageDigest.getInstance("SHA-256");
        var sha256Hash = sha256.digest((nonce + postData).getBytes(StandardCharsets.UTF_8));

        var mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(apiSecret), "HmacSHA512"));
        mac.update(path.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac.doFinal(sha256Hash));
    }

    private String encodeForm(Map<String, String> params) {
        var sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
