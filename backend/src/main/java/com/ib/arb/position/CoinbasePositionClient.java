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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CoinbasePositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(CoinbasePositionClient.class);
    private static final String BASE_URL = "https://api.exchange.coinbase.com";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public CoinbasePositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.COINBASE; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("COINBASE").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            var ts      = String.valueOf(System.currentTimeMillis() / 1000L);
            var method  = "GET";
            var path    = "/accounts";
            var body    = "";
            var prehash = ts + method + path + body;
            var sig     = hmacBase64(cfg.getApiSecret(), prehash);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("CB-ACCESS-KEY", cfg.getApiKey())
                .header("CB-ACCESS-SIGN", sig)
                .header("CB-ACCESS-TIMESTAMP", ts)
                .header("CB-ACCESS-PASSPHRASE", cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "")
                .header("Content-Type", "application/json")
                .GET().build();

            var root     = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var balances = new ConcurrentHashMap<String, Double>();
            if (root.isArray()) {
                root.forEach(a -> {
                    var bal = a.path("available").asDouble();
                    if (bal > 0) balances.put(a.path("currency").asText(), bal);
                });
            }
            return balances;
        } catch (Exception e) {
            log.error("[COINBASE] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("COINBASE").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var ts      = String.valueOf(System.currentTimeMillis() / 1000L);
            var method  = "GET";
            var path    = "/orders?status=open";
            var prehash = ts + method + path + "";
            var sig     = hmacBase64(cfg.getApiSecret(), prehash);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("CB-ACCESS-KEY", cfg.getApiKey())
                .header("CB-ACCESS-SIGN", sig)
                .header("CB-ACCESS-TIMESTAMP", ts)
                .header("CB-ACCESS-PASSPHRASE", cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "")
                .header("Content-Type", "application/json")
                .GET().build();

            var root   = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var orders = new ArrayList<OpenOrder>();
            if (root.isArray()) {
                root.forEach(o -> orders.add(new OpenOrder(
                    o.path("id").asText(),
                    o.path("product_id").asText().replace("-", ""),
                    o.path("side").asText().toLowerCase(),
                    o.path("type").asText().toLowerCase(),
                    o.path("price").asDouble(),
                    o.path("size").asDouble(),
                    o.path("filled_size").asDouble(),
                    0.0,
                    o.path("status").asText().toLowerCase()
                )));
            }
            return orders;
        } catch (Exception e) {
            log.error("[COINBASE] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        var secretBytes = Base64.getDecoder().decode(secret);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
