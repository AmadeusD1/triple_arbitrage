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
public class BitfinexPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(BitfinexPositionClient.class);
    private static final String BASE_URL = "https://api.bitfinex.com";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BitfinexPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BITFINEX; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("BITFINEX").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            var nonce   = String.valueOf(System.currentTimeMillis());
            var path    = "/v2/auth/r/wallets";
            var payload = "/api" + path + nonce;
            var sig     = hmac384(cfg.getApiSecret(), payload);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("bfx-apikey",    cfg.getApiKey())
                .header("bfx-signature", sig)
                .header("bfx-nonce",     nonce)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

            var root     = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var balances = new ConcurrentHashMap<String, Double>();
            // Response: [[wallet_type, currency, balance, unsettled_interest, available, ...]]
            if (root.isArray()) {
                root.forEach(w -> {
                    var available = w.size() > 4 ? w.get(4).asDouble() : w.get(2).asDouble();
                    if (available > 0) {
                        var currency = w.get(1).asText().toUpperCase();
                        balances.merge(currency, available, Double::sum);
                    }
                });
            }
            return balances;
        } catch (Exception e) {
            log.error("[BITFINEX] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("BITFINEX").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var nonce   = String.valueOf(System.currentTimeMillis());
            var path    = "/v2/auth/r/orders";
            var payload = "/api" + path + nonce;
            var sig     = hmac384(cfg.getApiSecret(), payload);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("bfx-apikey",    cfg.getApiKey())
                .header("bfx-signature", sig)
                .header("bfx-nonce",     nonce)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

            var root   = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var orders = new ArrayList<OpenOrder>();
            if (root.isArray()) {
                root.forEach(o -> {
                    var symbol = o.get(3).asText().startsWith("t") ? o.get(3).asText().substring(1) : o.get(3).asText();
                    var amount = o.get(6).asDouble();
                    orders.add(new OpenOrder(
                        o.get(0).asText(),
                        symbol,
                        amount >= 0 ? "buy" : "sell",
                        "limit",
                        o.get(16).asDouble(),
                        Math.abs(amount),
                        Math.abs(o.get(7).asDouble()),
                        o.get(4).asDouble(),
                        "open"
                    ));
                });
            }
            return orders;
        } catch (Exception e) {
            log.error("[BITFINEX] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String hmac384(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA384");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
