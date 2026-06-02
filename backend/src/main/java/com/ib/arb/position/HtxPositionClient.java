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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HtxPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(HtxPositionClient.class);
    private static final String HOST    = "api.huobi.pro";
    private static final String BASE_URL = "https://" + HOST;
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ExchangeConfigRepository configRepo;
    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public HtxPositionClient(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.HTX; }

    @Override
    public Map<String, Double> fetchBalances() {
        try {
            var cfg = configRepo.findByExchange("HTX").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            // First fetch account list to get account id
            var accountId = fetchSpotAccountId(cfg.getApiKey(), cfg.getApiSecret());
            if (accountId == null) return Map.of();

            var path    = "/v1/account/accounts/" + accountId + "/balance";
            var qs      = buildQueryString(cfg.getApiKey(), "GET", path, "");
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + "?" + qs))
                .GET().build();

            var root     = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var balances = new ConcurrentHashMap<String, Double>();
            root.path("data").path("list").forEach(b -> {
                if ("trade".equals(b.path("type").asText())) {
                    var bal = b.path("balance").asDouble();
                    if (bal > 0) balances.put(b.path("currency").asText().toUpperCase(), bal);
                }
            });
            return balances;
        } catch (Exception e) {
            log.error("[HTX] fetchBalances failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private String fetchSpotAccountId(String apiKey, String apiSecret) {
        try {
            var path = "/v1/account/accounts";
            var qs   = buildQueryString(apiKey, "GET", path, "");
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

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        try {
            var cfg = configRepo.findByExchange("HTX").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var path    = "/v1/order/openOrders";
            var qs      = buildQueryString(cfg.getApiKey(), "GET", path, "");
            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + "?" + qs))
                .GET().build();

            var root   = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var orders = new ArrayList<OpenOrder>();
            root.path("data").forEach(o -> orders.add(new OpenOrder(
                o.path("id").asText(),
                o.path("symbol").asText().toUpperCase(),
                o.path("type").asText().contains("buy") ? "buy" : "sell",
                "limit",
                o.path("price").asDouble(),
                o.path("amount").asDouble(),
                o.path("field-amount").asDouble(),
                o.path("created-at").asDouble(),
                "open"
            )));
            return orders;
        } catch (Exception e) {
            log.error("[HTX] fetchOpenOrders failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Build signed query string for HTX REST v1 authentication. */
    private String buildQueryString(String apiKey, String method, String path, String extraParams) throws Exception {
        var ts  = FMT.format(ZonedDateTime.now(ZoneOffset.UTC));
        var sb  = new StringBuilder();
        sb.append("AccessKeyId=").append(enc(apiKey));
        sb.append("&SignatureMethod=HmacSHA256");
        sb.append("&SignatureVersion=2");
        sb.append("&Timestamp=").append(enc(ts));
        if (!extraParams.isEmpty()) sb.append("&").append(extraParams);

        var toSign = method + "\n" + HOST + "\n" + path + "\n" + sb;
        var cfg    = configRepo.findByExchange("HTX").orElse(null);
        var sig    = hmac256(cfg != null ? cfg.getApiSecret() : "", toSign);
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
