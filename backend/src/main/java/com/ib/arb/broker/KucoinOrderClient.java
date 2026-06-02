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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class KucoinOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(KucoinOrderClient.class);
    private static final String BASE_URL = "https://api.kucoin.com";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    public KucoinOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.KUCOIN; }

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
            // BTCUSDT → BTC-USDT
            String kcSymbol;
            if (pair.endsWith("USDT") && pair.length() > 4) kcSymbol = pair.substring(0, pair.length() - 4) + "-USDT";
            else if (pair.endsWith("BTC") && pair.length() > 3) kcSymbol = pair.substring(0, pair.length() - 3) + "-BTC";
            else if (pair.endsWith("ETH") && pair.length() > 3) kcSymbol = pair.substring(0, pair.length() - 3) + "-ETH";
            else kcSymbol = pair.length() > 3 ? pair.substring(0, pair.length() - 3) + "-" + pair.substring(pair.length() - 3) : pair;

            var body    = mapper.createObjectNode()
                .put("clientOid", UUID.randomUUID().toString())
                .put("side",      side)
                .put("symbol",    kcSymbol)
                .put("type",      "limit")
                .put("price",     String.format("%.8f", price))
                .put("size",      String.format("%.6f", qty));
            var bodyStr = mapper.writeValueAsString(body);
            var path    = "/api/v1/orders";
            var ts      = String.valueOf(System.currentTimeMillis());
            var prehash = ts + "POST" + path + bodyStr;
            var sig     = hmacBase64(apiSecret(), prehash);

            var cfg = configRepo.findByExchange("KUCOIN").orElse(null);
            var pp  = hmacBase64(apiSecret(), cfg != null && cfg.getApiPassphrase() != null ? cfg.getApiPassphrase() : "");

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("KC-API-KEY",         apiKey())
                .header("KC-API-SIGN",        sig)
                .header("KC-API-TIMESTAMP",   ts)
                .header("KC-API-PASSPHRASE",  pp)
                .header("KC-API-KEY-VERSION", "2")
                .header("Content-Type",       "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("data")) return Optional.of(node.path("data").path("orderId").asText());
        } catch (Exception e) {
            log.error("[KUCOIN] sendOrder failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
