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
public class BybitPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(BybitPositionClient.class);
    private static final String BASE_URL    = "https://api.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final ExchangeConfigRepository configRepo;
    private final HttpClient   http   = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BybitPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BYBIT; }

    @Override
    public Map<String, Double> fetchBalances() {
        String rawBody = null;
        try {
            var cfg = configRepo.findByExchange("BYBIT").orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) return Map.of();

            var ts    = String.valueOf(System.currentTimeMillis());
            var query = "accountType=SPOT";
            var sign  = sign(ts, cfg.getApiKey(), RECV_WINDOW, query, cfg.getApiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v5/account/wallet-balance?" + query))
                .header("X-BAPI-API-KEY",      cfg.getApiKey())
                .header("X-BAPI-SIGN",         sign)
                .header("X-BAPI-SIGN-TYPE",    "2")
                .header("X-BAPI-TIMESTAMP",    ts)
                .header("X-BAPI-RECV-WINDOW",  RECV_WINDOW)
                .GET()
                .build();

            rawBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            var root = mapper.readTree(rawBody);
            if (root.path("retCode").asInt() != 0) {
                log.error("[BYBIT] fetchBalances error: {}", root.path("retMsg").asText());
                return Map.of();
            }

            var balances = new ConcurrentHashMap<String, Double>();
            var coins = root.path("result").path("list").get(0).path("coin");
            for (var coin : coins) {
                var asset = coin.path("coin").asText();
                var bal   = coin.path("walletBalance").asDouble();
                if (bal > 0) balances.put(asset, bal);
            }
            return balances;
        } catch (Exception e) {
            log.error("[BYBIT] fetchBalances failed: {} | raw='{}'", e.getMessage(),
                rawBody != null ? rawBody.substring(0, Math.min(200, rawBody.length())) : "null");
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("BYBIT").orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) return List.of();

            var ts    = String.valueOf(System.currentTimeMillis());
            var query = "category=spot";
            var sign  = sign(ts, cfg.getApiKey(), RECV_WINDOW, query, cfg.getApiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v5/order/realtime?" + query))
                .header("X-BAPI-API-KEY",      cfg.getApiKey())
                .header("X-BAPI-SIGN",         sign)
                .header("X-BAPI-SIGN-TYPE",    "2")
                .header("X-BAPI-TIMESTAMP",    ts)
                .header("X-BAPI-RECV-WINDOW",  RECV_WINDOW)
                .GET()
                .build();

            var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (root.path("retCode").asInt() != 0) {
                log.error("[BYBIT] fetchOpenOrders error: {}", root.path("retMsg").asText());
                return List.of();
            }

            var orders = new ArrayList<OpenOrder>();
            for (var o : root.path("result").path("list")) {
                var symbol = o.path("symbol").asText();
                // EURUSDT → EURUSD
                var pair = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 1) : symbol;
                orders.add(new OpenOrder(
                    o.path("orderId").asText(),
                    pair,
                    o.path("side").asText().toLowerCase(),
                    o.path("orderType").asText().toLowerCase(),
                    o.path("price").asDouble(),
                    o.path("qty").asDouble(),
                    o.path("cumExecQty").asDouble(),
                    o.path("createdTime").asDouble(),
                    o.path("orderStatus").asText("open").toLowerCase()
                ));
            }
            return orders;
        } catch (Exception e) {
            log.error("[BYBIT] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    // Sign string: timestamp + apiKey + recvWindow + queryStringOrBody
    static String sign(String timestamp, String apiKey, String recvWindow,
                       String payload, String apiSecret) throws Exception {
        var raw = timestamp + apiKey + recvWindow + payload;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
