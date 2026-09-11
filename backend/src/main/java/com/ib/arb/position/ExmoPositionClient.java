package com.ib.arb.position;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.ExchangeConfigRepository;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EXMO account-state client.
 * Auth: Key = apiKey header, Sign = hex(HMAC-SHA512(urlEncodedPostBody, apiSecret)) header,
 * same scheme as {@link com.ib.arb.broker.ExmoOrderClient}.
 * Balances: POST /v1.1/user_info -> "balances" object keyed by ISO currency code.
 * Open orders: POST /v1.1/user_open_orders -> object keyed by "BASE_QUOTE" pair, value = array of orders.
 */
@Component
public class ExmoPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(ExmoPositionClient.class);
    private static final String BASE_URL = "https://api.exmo.com/v1.1";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nonce = new AtomicLong(System.currentTimeMillis());

    public ExmoPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.EXMO; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("EXMO").orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) return Map.of();

            var node = post(cfg.getApiKey(), cfg.getApiSecret(), "/user_info");
            if (!node.path("balances").isObject()) {
                log.warn("[EXMO] fetchBalances: unexpected response: {}", node);
                return Map.of();
            }
            var balances = new ConcurrentHashMap<String, Double>();
            node.path("balances").properties().forEach(e -> {
                var amount = e.getValue().asDouble();
                if (amount > 0) balances.put(e.getKey().toUpperCase(), amount);
            });
            return balances;
        } catch (Exception e) {
            log.error("[EXMO] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("EXMO").orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) return List.of();

            var node = post(cfg.getApiKey(), cfg.getApiSecret(), "/user_open_orders");
            if (!node.isObject()) return List.of();

            var orders = new ArrayList<OpenOrder>();
            node.properties().forEach(entry -> {
                var pair = entry.getKey().replace("_", "");
                for (var o : entry.getValue()) {
                    orders.add(new OpenOrder(
                        "EXMO",
                        String.valueOf(o.path("order_id").asLong()),
                        pair,
                        o.path("type").asText(),
                        "limit",
                        o.path("price").asDouble(),
                        o.path("quantity").asDouble(),
                        0.0,
                        o.path("created").asDouble(),
                        "open"
                    ));
                }
            });
            return orders;
        } catch (Exception e) {
            log.error("[EXMO] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private JsonNode post(String apiKey, String apiSecret, String path) throws Exception {
        var body = "nonce=" + nonce.incrementAndGet();
        var request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Key",          apiKey)
            .header("Sign",         sign(body, apiSecret))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    /** hex(HMAC-SHA512(postData, apiSecret)) */
    private static String sign(String postData, String apiSecret) throws Exception {
        var mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(postData.getBytes(StandardCharsets.UTF_8)));
    }
}
