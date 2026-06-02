package com.ib.arb.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class CoinbaseOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseOrderClient.class);
    private static final String BASE_URL = "https://api.exchange.coinbase.com";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    public CoinbaseOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.COINBASE; }

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var orderId = sendOrder(l.pair(), l.direction().toLowerCase(), l.price(), l.quantity());
                    return new LegResult(l.legIndex(), l.pair(), l.direction(),
                        l.price(), l.quantity(), orderId.isPresent(), orderId.orElse(null));
                }))
                .toList();
            return futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(LegResult::legIndex)).toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }

    private Optional<String> sendOrder(String pair, String side, double price, double qty) {
        try {
            // BTCUSD → BTC-USD
            var productId = pair.length() >= 4
                ? pair.substring(0, pair.length() - 3) + "-" + pair.substring(pair.length() - 3)
                : pair;
            var body = mapper.createObjectNode()
                .put("type", "limit")
                .put("side", side)
                .put("product_id", productId)
                .put("price", String.format("%.8f", price))
                .put("size", String.format("%.6f", qty))
                .put("time_in_force", "GTC");
            var bodyStr = mapper.writeValueAsString(body);
            var ts      = String.valueOf(System.currentTimeMillis() / 1000L);
            var prehash = ts + "POST" + "/orders" + bodyStr;
            var sig     = hmacBase64(apiSecret(), prehash);

            var cfg = configRepo.findByExchange("COINBASE").orElse(null);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/orders"))
                .header("CB-ACCESS-KEY", apiKey())
                .header("CB-ACCESS-SIGN", sig)
                .header("CB-ACCESS-TIMESTAMP", ts)
                .header("CB-ACCESS-PASSPHRASE", cfg != null && cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("id")) return Optional.of(node.path("id").asText());
        } catch (Exception e) {
            log.error("[COINBASE] sendOrder failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        var secretBytes = Base64.getDecoder().decode(secret);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
