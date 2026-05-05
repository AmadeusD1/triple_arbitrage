package com.ib.arb.position;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.broker.KrakenAuth;
import com.ib.arb.marketdata.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class KrakenPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenPositionClient.class);
    private static final String BASE_URL = "https://api.kraken.com";

    @Value("${kraken.api-key:}")
    private String apiKey;

    @Value("${kraken.api-secret:}")
    private String apiSecret;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Exchange getExchange() { return Exchange.KRAKEN; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var nonce = KrakenAuth.nextNonce();
            var body  = "nonce=" + nonce;
            var root  = mapper.readTree(send("/0/private/Balance", nonce, body));

            var errors = root.path("error");
            if (errors.isArray() && !errors.isEmpty()) {
                log.error("Kraken Balance error: {}", errors);
                return Map.of();
            }
            var result = root.path("result");
            if (result.isMissingNode()) {
                log.error("Kraken Balance: missing result field");
                return Map.of();
            }
            var balances = new ConcurrentHashMap<String, Double>();
            result.properties().forEach(e -> balances.put(e.getKey(), e.getValue().asDouble()));
            return balances;
        } catch (Exception e) {
            log.error("Failed to fetch Kraken balances: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var nonce = KrakenAuth.nextNonce();
            var body  = "nonce=" + nonce;
            var root  = mapper.readTree(send("/0/private/OpenOrders", nonce, body));

            if (!root.path("error").isEmpty()) {
                log.error("Kraken OpenOrders error: {}", root.path("error"));
                return List.of();
            }
            var orders = new ArrayList<OpenOrder>();
            root.path("result").path("open").properties().forEach(entry -> {
                var txid = entry.getKey();
                var o    = entry.getValue();
                var desc = o.path("descr");
                orders.add(new OpenOrder(
                    txid,
                    desc.path("pair").asText(),
                    desc.path("type").asText(),
                    desc.path("ordertype").asText(),
                    desc.path("price").asDouble(),
                    o.path("vol").asDouble(),
                    o.path("vol_exec").asDouble(),
                    o.path("opentm").asDouble(),
                    o.path("status").asText("open")
                ));
            });
            return orders;
        } catch (Exception e) {
            log.error("Failed to fetch Kraken open orders: {}", e.getMessage());
            return List.of();
        }
    }

    private String send(String path, String nonce, String body) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("API-Key", apiKey)
            .header("API-Sign", KrakenAuth.sign(path, nonce, body, apiSecret))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
