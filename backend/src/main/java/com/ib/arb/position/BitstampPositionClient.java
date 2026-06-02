package com.ib.arb.position;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BitstampPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(BitstampPositionClient.class);
    private static final String BASE_URL = "https://www.bitstamp.net";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BitstampPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BITSTAMP; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("BITSTAMP").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            var nonce     = UUID.randomUUID().toString().replace("-", "");
            var ts        = String.valueOf(System.currentTimeMillis());
            var contentType = "application/x-www-form-urlencoded";
            var stringToSign = "BITSTAMP " + cfg.getApiKey() +
                "\nPOST\nwww.bitstamp.net\n/api/v2/balance/\n" + contentType + "\n" + nonce + "\n" + ts + "\nv2\n\n";
            var sig = hmac256(cfg.getApiSecret(), stringToSign);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v2/balance/"))
                .header("X-Auth", "BITSTAMP " + cfg.getApiKey())
                .header("X-Auth-Signature", sig)
                .header("X-Auth-Nonce", nonce)
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Version", "v2")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var balances = new ConcurrentHashMap<String, Double>();
            root.fields().forEachRemaining(e -> {
                var key = e.getKey();
                if (key.endsWith("_available")) {
                    var asset = key.replace("_available", "").toUpperCase();
                    var val   = e.getValue().asDouble();
                    if (val > 0) balances.put(asset, val);
                }
            });
            return balances;
        } catch (Exception e) {
            log.error("[BITSTAMP] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("BITSTAMP").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var nonce       = UUID.randomUUID().toString().replace("-", "");
            var ts          = String.valueOf(System.currentTimeMillis());
            var contentType = "application/x-www-form-urlencoded";
            var stringToSign = "BITSTAMP " + cfg.getApiKey() +
                "\nPOST\nwww.bitstamp.net\n/api/v2/open_orders/all/\n" + contentType + "\n" + nonce + "\n" + ts + "\nv2\n\n";
            var sig = hmac256(cfg.getApiSecret(), stringToSign);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v2/open_orders/all/"))
                .header("X-Auth", "BITSTAMP " + cfg.getApiKey())
                .header("X-Auth-Signature", sig)
                .header("X-Auth-Nonce", nonce)
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Version", "v2")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            var root   = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var orders = new ArrayList<OpenOrder>();
            if (root.isArray()) {
                root.forEach(o -> orders.add(new OpenOrder(
                    o.path("id").asText(),
                    o.path("currency_pair").asText().replace("/", "").toUpperCase(),
                    o.path("type").asInt() == 0 ? "buy" : "sell",
                    "limit",
                    o.path("price").asDouble(),
                    o.path("amount").asDouble(),
                    0.0,
                    o.path("datetime").asDouble(),
                    "open"
                )));
            }
            return orders;
        } catch (Exception e) {
            log.error("[BITSTAMP] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }
}
