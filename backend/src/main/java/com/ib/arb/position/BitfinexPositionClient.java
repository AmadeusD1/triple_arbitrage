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
        String rawBody = null;
        try {
            var cfg = configRepo.findByExchange("BITFINEX").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return Map.of();

            var nonce   = String.valueOf(System.currentTimeMillis());
            var path    = "/v2/auth/r/wallets";
            var bodyStr = "{}";
            var payload = "/api" + path + nonce + bodyStr;
            var sig     = hmac384(cfg.getApiSecret(), payload);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("bfx-apikey",    cfg.getApiKey())
                .header("bfx-signature", sig)
                .header("bfx-nonce",     nonce)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            rawBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            var root     = mapper.readTree(rawBody);
            var balances = new ConcurrentHashMap<String, Double>();
            // Success: [[wallet_type, currency, balance, unsettled_interest, available, ...], ...]
            // Auth/other errors instead arrive as e.g. [MTS,"error",null,null,[...],null,"ERROR","<message>"]
            // - not a list of wallet arrays - so only treat top-level elements that are themselves
            // arrays as wallets, and log anything else raw instead of crashing on it.
            if (root.isArray()) {
                var recognizedAny = false;
                for (var w : root) {
                    if (!w.isArray() || w.size() < 3) continue;
                    recognizedAny = true;
                    var available = (w.size() > 4 && !w.get(4).isNull()) ? w.get(4).asDouble() : w.get(2).asDouble();
                    if (available > 0) {
                        var currency = w.get(1).asText().toUpperCase();
                        balances.merge(currency, available, Double::sum);
                    }
                }
                // Positive confirmation the key/secret authenticated successfully in every case,
                // including a genuinely empty "[]" (e.g. a brand-new account with no wallets ever
                // created yet) - otherwise this call produces no log output at all and looks
                // identical to never having run, or to a silent failure.
                if (root.isEmpty()) {
                    log.info("[BITFINEX] fetchBalances: authenticated OK, account has no wallets yet");
                } else if (!recognizedAny) {
                    log.error("[BITFINEX] fetchBalances: unrecognized response shape: {}", rawBody);
                } else {
                    log.info("[BITFINEX] fetchBalances: authenticated OK, {} wallet(s), {} with positive balance",
                        root.size(), balances.size());
                }
            }
            return balances;
        } catch (Exception e) {
            log.error("[BITFINEX] fetchBalances failed: {} | raw='{}'", e.getMessage(), rawBody);
            return Map.of();
        }
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        String rawBody = null;
        try {
            var cfg = configRepo.findByExchange("BITFINEX").orElse(null);
            if (cfg == null || cfg.getApiKey() == null) return List.of();

            var nonce   = String.valueOf(System.currentTimeMillis());
            var path    = "/v2/auth/r/orders";
            var bodyStr = "{}";
            var payload = "/api" + path + nonce + bodyStr;
            var sig     = hmac384(cfg.getApiSecret(), payload);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("bfx-apikey",    cfg.getApiKey())
                .header("bfx-signature", sig)
                .header("bfx-nonce",     nonce)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

            rawBody = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            var root   = mapper.readTree(rawBody);
            var orders = new ArrayList<OpenOrder>();
            if (root.isArray()) {
                for (var o : root) {
                    // Auth/other errors arrive as a flat array (e.g. [..., "ERROR", "<message>"]),
                    // not a list of order arrays - skip anything that isn't order-shaped.
                    if (!o.isArray() || o.size() < 17) continue;
                    var symbol = fromBfxSymbol(o.get(3).asText());
                    var amount = o.get(6).asDouble();
                    orders.add(new OpenOrder(
                        "BITFINEX",
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
                }
            }
            return orders;
        } catch (Exception e) {
            log.error("[BITFINEX] fetchOpenOrders failed: {} | raw='{}'", e.getMessage(), rawBody);
            return List.of();
        }
    }

    // Bitfinex uses its own 3-letter codes for USDT ("UST") and USDC ("UDC"); see
    // BitfinexOrderClient/BitfinexOrderBookFeed for the matching outbound conversion.
    private static String fromBfxCode(String code) {
        return switch (code) {
            case "UST" -> "USDT";
            case "UDC" -> "USDC";
            default -> code;
        };
    }

    /** Reverses the outbound symbol conversion: tBTCUST -> BTCUSDT, tDOGE:USD -> DOGEUSD. */
    private static String fromBfxSymbol(String symbol) {
        var body = symbol.startsWith("t") ? symbol.substring(1) : symbol;
        String base, quote;
        if (body.contains(":")) {
            var parts = body.split(":", 2);
            base = parts[0];
            quote = parts[1];
        } else {
            base = body.substring(0, 3);
            quote = body.substring(3);
        }
        return fromBfxCode(base) + fromBfxCode(quote);
    }

    private static String hmac384(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA384");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
