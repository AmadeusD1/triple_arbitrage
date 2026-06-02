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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class BitfinexOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BitfinexOrderClient.class);
    private static final String BASE_URL = "https://api.bitfinex.com";

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    public BitfinexOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BITFINEX; }

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
            // BTCUSD → tBTCUSD; buy = positive amount, sell = negative
            var symbol = "t" + pair.toUpperCase();
            var amount = "buy".equals(side) ? qty : -qty;
            var body   = mapper.createObjectNode()
                .put("type",   "EXCHANGE LIMIT")
                .put("symbol", symbol)
                .put("amount", String.format("%.6f", amount))
                .put("price",  String.format("%.8f", price));
            var bodyStr = mapper.writeValueAsString(body);
            var nonce   = String.valueOf(System.currentTimeMillis());
            var path    = "/v2/auth/w/order/submit";
            var payload = "/api" + path + nonce + bodyStr;
            var sig     = hmac384(apiSecret(), payload);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("bfx-apikey",    apiKey())
                .header("bfx-signature", sig)
                .header("bfx-nonce",     nonce)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            // Response: [mts, type, messageID, null, [[id, ...]]]
            if (node.isArray() && node.size() >= 5) {
                var orders = node.get(4);
                if (orders.isArray() && !orders.isEmpty()) {
                    return Optional.of(orders.get(0).get(0).asText());
                }
            }
        } catch (Exception e) {
            log.error("[BITFINEX] sendOrder failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String hmac384(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA384");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
