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
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class BybitOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(BybitOrderClient.class);
    private static final String BASE_URL    = "https://api.bybit.com";
    private static final String RECV_WINDOW = "5000";

    private final HttpClient   http   = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public BybitOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.BYBIT; }

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var orderId = sendOrder(l.pair(), l.direction(), l.price(), l.quantity());
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

    private Optional<String> sendOrder(String pair, String direction, double price, double qty) {
        try {
            // EURUSD → EURUSDT
            var symbol = pair.endsWith("USD") ? pair + "T" : pair;
            var side   = direction.equalsIgnoreCase("BUY") ? "Buy" : "Sell";

            var bodyNode = mapper.createObjectNode()
                .put("category",    "spot")
                .put("symbol",      symbol)
                .put("side",        side)
                .put("orderType",   "Limit")
                .put("qty",         String.format("%.6f", qty))
                .put("price",       String.format("%.8f", price))
                .put("timeInForce", "GTC");
            var body = mapper.writeValueAsString(bodyNode);

            var ts   = String.valueOf(System.currentTimeMillis());
            var sign = sign(ts, apiKey(), RECV_WINDOW, body, apiSecret());

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v5/order/create"))
                .header("Content-Type",       "application/json")
                .header("X-BAPI-API-KEY",     apiKey())
                .header("X-BAPI-SIGN",        sign)
                .header("X-BAPI-SIGN-TYPE",   "2")
                .header("X-BAPI-TIMESTAMP",   ts)
                .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            var root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (root.path("retCode").asInt() == 0) {
                return Optional.of(root.path("result").path("orderId").asText());
            }
            log.error("[BYBIT] sendOrder failed: {}", root.path("retMsg").asText());
        } catch (Exception e) {
            log.error("[BYBIT] sendOrder exception: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String sign(String timestamp, String apiKey, String recvWindow,
                                String payload, String apiSecret) throws Exception {
        var raw = timestamp + apiKey + recvWindow + payload;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
