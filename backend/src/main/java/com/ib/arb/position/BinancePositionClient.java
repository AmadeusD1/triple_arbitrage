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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BinancePositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(BinancePositionClient.class);
    private static final String BASE_URL = "https://api.binance.com";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http   = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BinancePositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BINANCE; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("BINANCE").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            var ts  = String.valueOf(System.currentTimeMillis());
            var qs  = "timestamp=" + ts;
            var sig = hmac256(cfg.getApiSecret(), qs);
            var url = BASE_URL + "/api/v3/account?" + qs + "&signature=" + sig;

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", cfg.getApiKey())
                .GET().build();

            var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var balances = new ConcurrentHashMap<String, Double>();
            root.path("balances").forEach(b -> {
                var free = b.path("free").asDouble();
                if (free > 0) balances.put(b.path("asset").asText(), free);
            });
            return balances;
        } catch (Exception e) {
            log.error("[BINANCE] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("BINANCE").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var ts  = String.valueOf(System.currentTimeMillis());
            var qs  = "timestamp=" + ts;
            var sig = hmac256(cfg.getApiSecret(), qs);
            var url = BASE_URL + "/api/v3/openOrders?" + qs + "&signature=" + sig;

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", cfg.getApiKey())
                .GET().build();

            var root   = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var orders = new ArrayList<OpenOrder>();
            if (root.isArray()) {
                root.forEach(o -> orders.add(new OpenOrder(
                    o.path("orderId").asText(),
                    o.path("symbol").asText(),
                    o.path("side").asText().toLowerCase(),
                    o.path("type").asText().toLowerCase(),
                    o.path("price").asDouble(),
                    o.path("origQty").asDouble(),
                    o.path("executedQty").asDouble(),
                    o.path("time").asDouble(),
                    o.path("status").asText().toLowerCase()
                )));
            }
            return orders;
        } catch (Exception e) {
            log.error("[BINANCE] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
