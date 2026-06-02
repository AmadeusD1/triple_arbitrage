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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class KucoinPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(KucoinPositionClient.class);
    private static final String BASE_URL = "https://api.kucoin.com";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public KucoinPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.KUCOIN; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("KUCOIN").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            var path    = "/api/v1/accounts?type=trade";
            var ts      = String.valueOf(System.currentTimeMillis());
            var prehash = ts + "GET" + path;
            var sig     = hmacBase64(cfg.getApiSecret(), prehash);
            var pp      = hmacBase64(cfg.getApiSecret(), cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "");

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("KC-API-KEY",         cfg.getApiKey())
                .header("KC-API-SIGN",        sig)
                .header("KC-API-TIMESTAMP",   ts)
                .header("KC-API-PASSPHRASE",  pp)
                .header("KC-API-KEY-VERSION", "2")
                .GET().build();

            var root     = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var balances = new ConcurrentHashMap<String, Double>();
            root.path("data").forEach(a -> {
                var avail = a.path("available").asDouble();
                if (avail > 0) balances.put(a.path("currency").asText().toUpperCase(), avail);
            });
            return balances;
        } catch (Exception e) {
            log.error("[KUCOIN] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("KUCOIN").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var path    = "/api/v1/orders?status=active";
            var ts      = String.valueOf(System.currentTimeMillis());
            var prehash = ts + "GET" + path;
            var sig     = hmacBase64(cfg.getApiSecret(), prehash);
            var pp      = hmacBase64(cfg.getApiSecret(), cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "");

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("KC-API-KEY",         cfg.getApiKey())
                .header("KC-API-SIGN",        sig)
                .header("KC-API-TIMESTAMP",   ts)
                .header("KC-API-PASSPHRASE",  pp)
                .header("KC-API-KEY-VERSION", "2")
                .GET().build();

            var root   = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var orders = new ArrayList<OpenOrder>();
            root.path("data").path("items").forEach(o -> orders.add(new OpenOrder(
                o.path("id").asText(),
                o.path("symbol").asText().replace("-", "").toUpperCase(),
                o.path("side").asText().toLowerCase(),
                o.path("type").asText().toLowerCase(),
                o.path("price").asDouble(),
                o.path("size").asDouble(),
                o.path("dealSize").asDouble(),
                o.path("createdAt").asDouble(),
                "active"
            )));
            return orders;
        } catch (Exception e) {
            log.error("[KUCOIN] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
