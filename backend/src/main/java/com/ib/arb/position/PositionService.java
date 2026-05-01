package com.ib.arb.position;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.Exchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieves and caches exchange account balances used for pre-trade validation.
 *
 * <p>Before each order leg is placed, {@link #hasAvailableBalance} is called to confirm
 * the exchange account holds enough of the spent currency. Balances are fetched from the
 * Kraken REST API ({@code /0/private/Balance}) and cached for {@code kraken.position-cache-ttl-ms}
 * milliseconds (default: 2 s) to avoid hitting rate limits on every scan cycle.
 *
 * <p>A {@link Scheduled} background task also refreshes the cache periodically so that the
 * balance is warm before the next arbitrage cycle runs.
 */
@Service
public class PositionService {

    private static final Map<String, String> KRAKEN_ASSET = Map.of(
        "USD", "ZUSD",
        "EUR", "ZEUR",
        "JPY", "ZJPY",
        "GBP", "ZGBP",
        "AUD", "ZAUD",
        "CAD", "ZCAD",
        "CHF", "ZCHF",
        "NZD", "ZNZD"
    );

    @Value("${kraken.api-key:}")
    private String apiKey;

    @Value("${kraken.api-secret:}")
    private String apiSecret;

    @Value("${kraken.position-cache-ttl-ms:2000}")
    private long cacheTtlMs;

    private final Map<Exchange, Map<String, Double>> balanceCache = new ConcurrentHashMap<>();
    private final Map<Exchange, Long> lastRefreshed = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns {@code true} if the exchange account holds at least {@code requiredAmount}
     * of the given ISO currency.
     *
     * <p>Triggers a live refresh if the cached balance is older than the configured TTL.
     * ISO currency codes are translated to Kraken asset keys automatically
     * (e.g. {@code "USD"} → {@code "ZUSD"}).
     *
     * @param exchange       the exchange to check (currently only {@code KRAKEN})
     * @param isoCurrency    ISO 4217 currency code of the asset to check (e.g. {@code "EUR"})
     * @param requiredAmount minimum balance required in that currency
     * @return {@code true} if sufficient balance is available; {@code false} if the balance
     *         is insufficient or the API call failed
     */
    public boolean hasAvailableBalance(Exchange exchange, String isoCurrency, double requiredAmount) {
        if (isStale(exchange)) refreshBalances(exchange);
        var balances = balanceCache.getOrDefault(exchange, Map.of());
        var krakenKey = KRAKEN_ASSET.getOrDefault(isoCurrency, isoCurrency);
        return balances.getOrDefault(krakenKey, 0.0) >= requiredAmount;
    }

    /**
     * Scheduled background refresh — keeps the balance cache warm so the first
     * {@link #hasAvailableBalance} call in a cycle does not incur a live API round-trip.
     * Runs at an interval equal to {@code kraken.position-cache-ttl-ms}.
     */
    @Scheduled(fixedDelayString = "${kraken.position-cache-ttl-ms:2000}")
    public void scheduledRefresh() {
        refreshBalances(Exchange.KRAKEN);
    }

    /**
     * Triggers an immediate balance refresh for the given exchange.
     *
     * @param exchange exchange whose balances should be refreshed
     */
    public void refreshBalances(Exchange exchange) {
        if (exchange == Exchange.KRAKEN) refreshKraken();
    }

    private boolean isStale(Exchange exchange) {
        var last = lastRefreshed.get(exchange);
        return last == null || System.currentTimeMillis() - last > cacheTtlMs;
    }

    private void refreshKraken() {
        try {
            var nonce = String.valueOf(System.currentTimeMillis());
            var postBody = "nonce=" + nonce;
            var path = "/0/private/Balance";

            var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.kraken.com" + path))
                .header("API-Key", apiKey)
                .header("API-Sign", sign(path, nonce, postBody))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(postBody))
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var root = mapper.readTree(response.body());
            var result = root.path("result");
            if (result.isMissingNode()) return;

            var balances = new ConcurrentHashMap<String, Double>();
            result.fields().forEachRemaining(e -> balances.put(e.getKey(), e.getValue().asDouble()));

            balanceCache.put(Exchange.KRAKEN, balances);
            lastRefreshed.put(Exchange.KRAKEN, System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    // Kraken HMAC-SHA512 signing:  HMAC-SHA512( path + SHA256(nonce + body), base64_decode(secret) )
    private String sign(String path, String nonce, String postBody) throws Exception {
        var sha256 = MessageDigest.getInstance("SHA-256");
        var shaDigest = sha256.digest((nonce + postBody).getBytes(StandardCharsets.UTF_8));

        var pathBytes = path.getBytes(StandardCharsets.UTF_8);
        var message = new byte[pathBytes.length + shaDigest.length];
        System.arraycopy(pathBytes, 0, message, 0, pathBytes.length);
        System.arraycopy(shaDigest, 0, message, pathBytes.length, shaDigest.length);

        var mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(apiSecret), "HmacSHA512"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message));
    }
}
