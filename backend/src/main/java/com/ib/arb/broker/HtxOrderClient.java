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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class HtxOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(HtxOrderClient.class);
    private static final String HOST     = "api.huobi.pro";
    private static final String BASE_URL = "https://" + HOST;
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HttpClient http     = HttpClient.newHttpClient();
    private final ObjectMapper mapper  = new ObjectMapper();

    public HtxOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        super(settings, configRepo);
    }

    @Override public Exchange getExchange() { return Exchange.HTX; }

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
            var accountId = fetchSpotAccountId();
            if (accountId == null) return Optional.empty();

            var path    = "/v1/order/orders/place";
            var qs      = buildQueryString("POST", path);
            var body    = mapper.createObjectNode()
                .put("account-id", accountId)
                .put("symbol",     pair.toLowerCase())
                .put("type",       side + "-limit")
                .put("amount",     String.format("%.6f", qty))
                .put("price",      String.format("%.8f", price))
                .put("source",     "spot-api");
            var bodyStr = mapper.writeValueAsString(body);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + "?" + qs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            var node = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if ("ok".equals(node.path("status").asText())) {
                return Optional.of(node.path("data").asText());
            }
        } catch (Exception e) {
            log.error("[HTX] sendOrder failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private String fetchSpotAccountId() {
        try {
            var path = "/v1/account/accounts";
            var qs   = buildQueryString("GET", path);
            var req  = HttpRequest.newBuilder().uri(URI.create(BASE_URL + path + "?" + qs)).GET().build();
            var root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            for (var a : root.path("data")) {
                if ("spot".equals(a.path("type").asText()) && "working".equals(a.path("state").asText())) {
                    return a.path("id").asText();
                }
            }
        } catch (Exception e) {
            log.error("[HTX] fetchSpotAccountId failed: {}", e.getMessage());
        }
        return null;
    }

    private String buildQueryString(String method, String path) throws Exception {
        var ts  = FMT.format(ZonedDateTime.now(ZoneOffset.UTC));
        var sb  = new StringBuilder();
        sb.append("AccessKeyId=").append(enc(apiKey()));
        sb.append("&SignatureMethod=HmacSHA256");
        sb.append("&SignatureVersion=2");
        sb.append("&Timestamp=").append(enc(ts));

        var toSign = method + "\n" + HOST + "\n" + path + "\n" + sb;
        var sig    = hmac256(apiSecret(), toSign);
        sb.append("&Signature=").append(enc(sig));
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hmac256(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
