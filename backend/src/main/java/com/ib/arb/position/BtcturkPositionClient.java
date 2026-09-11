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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BtcTurk account-state client.
 * Auth: X-PCK = apiKey, X-Stamp = millis, X-Signature = base64(HMAC-SHA256(apiKey+stamp, base64Decode(apiSecret))),
 * same scheme as {@link com.ib.arb.broker.BtcturkOrderClient}.
 * Balances: GET /api/v1/users/balances -> data[] with asset/free.
 * Open orders: GET /api/v1/openOrders -> data.bids[] + data.asks[].
 */
@Component
public class BtcturkPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(BtcturkPositionClient.class);
    private static final String BASE_URL = "https://api.btcturk.com";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BtcturkPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BTCTURK; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("BTCTURK").orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) return Map.of();

            var node = get(cfg.getApiKey(), cfg.getApiSecret(), "/api/v1/users/balances");
            var balances = new ConcurrentHashMap<String, Double>();
            for (var a : node.path("data")) {
                var free = a.path("free").asDouble(a.path("balance").asDouble());
                if (free > 0) balances.put(a.path("asset").asText().toUpperCase(), free);
            }
            return balances;
        } catch (Exception e) {
            log.error("[BTCTURK] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("BTCTURK").orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) return List.of();

            var node = get(cfg.getApiKey(), cfg.getApiSecret(), "/api/v1/openOrders");
            var orders = new ArrayList<OpenOrder>();
            for (var side : List.of("bids", "asks")) {
                for (var o : node.path("data").path(side)) {
                    var qty  = o.path("quantity").asDouble(o.path("amount").asDouble());
                    var left = o.path("leftAmount").asDouble(qty);
                    orders.add(new OpenOrder(
                        "BTCTURK",
                        String.valueOf(o.path("id").asLong()),
                        o.path("pairSymbol").asText(),
                        o.path("type").asText().toLowerCase(),
                        o.path("method").asText("limit").toLowerCase(),
                        o.path("price").asDouble(),
                        qty,
                        qty - left,
                        o.path("time").asDouble(),
                        o.path("status").asText("open")
                    ));
                }
            }
            return orders;
        } catch (Exception e) {
            log.error("[BTCTURK] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private JsonNode get(String apiKey, String apiSecret, String path) throws Exception {
        var ts = String.valueOf(System.currentTimeMillis());
        var request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("X-PCK",       apiKey)
            .header("X-Stamp",     ts)
            .header("X-Signature", sign(apiKey, apiSecret, ts))
            .GET().build();
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    /** base64(HMAC-SHA256(apiKey + stamp, base64Decode(apiSecret))) */
    private static String sign(String apiKey, String apiSecret, String stamp) throws Exception {
        var decodedSecret = Base64.getDecoder().decode(apiSecret);
        var message = (apiKey + stamp).getBytes(StandardCharsets.UTF_8);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(decodedSecret, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message));
    }
}
