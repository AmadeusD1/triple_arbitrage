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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class BitstampOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BitstampOrderClient.class);
    private static final String BASE_URL = "https://www.bitstamp.net";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    public BitstampOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BITSTAMP; }

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
            // Bitstamp pair: EURUSD → eurusd
            var bsPair      = pair.toLowerCase();
            var path        = "/api/v2/" + side + "/limit/" + bsPair + "/";
            var body        = "amount=" + String.format("%.6f", qty) + "&price=" + String.format("%.5f", price);
            var nonce       = UUID.randomUUID().toString().replace("-", "");
            var ts          = String.valueOf(System.currentTimeMillis());
            var contentType = "application/x-www-form-urlencoded";
            var stringToSign = "BITSTAMP " + apiKey() +
                "\nPOST\nwww.bitstamp.net\n" + path + "\n" + contentType + "\n" + nonce + "\n" + ts + "\nv2\n" + body;
            var sig = hmac256(apiSecret(), stringToSign);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("X-Auth", "BITSTAMP " + apiKey())
                .header("X-Auth-Signature", sig)
                .header("X-Auth-Nonce", nonce)
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Version", "v2")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (node.has("id")) return Optional.of(node.path("id").asText());
        } catch (Exception e) {
            log.error("[BITSTAMP] sendOrder failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }
}
