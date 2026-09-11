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

    // Bitfinex's precision rule is platform-wide and significant-figure based (not a fixed
    // per-pair decimal count fetchable from a clean API field): prices are limited to 5
    // significant digits, capped at 8 decimal places; order amount allows up to 8 decimals.
    @Override public int[] getPrecision(String pair) { return new int[]{8, 8}; }

    private static final int PRICE_SIG_FIGS   = 5;
    private static final int PRICE_MAX_DECIMALS = 8;
    private static final int AMOUNT_DECIMALS  = 8;

    // Longest-first so "USDT" matches before "USD", "USDC" before "USD", etc.
    // Needed to correctly split a concatenated internal pair like "BTCUSDT" into
    // base/quote (a plain first-3-chars split mangles 4+ letter currencies).
    private static final List<String> QUOTE_SUFFIXES = List.of(
        "USDT", "USDC", "BUSD", "EUR", "GBP", "JPY", "TRY", "USD", "BTC", "ETH"
    );

    private static String[] splitPair(String pair) {
        var norm = pair.toUpperCase();
        for (var q : QUOTE_SUFFIXES) {
            if (norm.endsWith(q) && norm.length() > q.length())
                return new String[]{ norm.substring(0, norm.length() - q.length()), q };
        }
        return new String[]{ norm.substring(0, 3), norm.substring(3) };
    }

    // Bitfinex uses its own 3-letter codes for USDT ("UST") and USDC ("UDC") so that
    // common pairs stay in the compact fixed-width BASEQUOTE form (e.g. "USTUSD",
    // "UDCUSD", "BTCUST"). Any other currency longer than 3 letters (DOGE, AAVE, ...)
    // instead uses a colon-separated symbol (e.g. "DOGE:USD", "AAVE:USD").
    private static String toBfxCode(String ccy) {
        return switch (ccy) {
            case "USDT" -> "UST";
            case "USDC" -> "UDC";
            default -> ccy;
        };
    }

    /**
     * Internal pair BTCUSD → tBTCUSD, BTCUSDT → tBTCUST, DOGEUSD → tDOGE:USD for
     * Bitfinex (colon needed once either code exceeds 3 letters, to keep the
     * symbol unambiguous).
     */
    private static String toBfxSymbol(String pair) {
        var parts = splitPair(pair);
        var base = toBfxCode(parts[0]);
        var quote = toBfxCode(parts[1]);
        var separator = (base.length() == 3 && quote.length() == 3) ? "" : ":";
        return "t" + base + separator + quote;
    }

    /** Rounds to {@code sigFigs} significant digits, capped at {@code maxDecimals} decimal places. */
    private static double roundToSignificantFigures(double value, int sigFigs, int maxDecimals) {
        if (value == 0) return 0;
        var magnitude = Math.floor(Math.log10(Math.abs(value)));
        var decimals  = Math.max(0, Math.min(maxDecimals, (int) (sigFigs - 1 - magnitude)));
        return round(value, decimals);
    }

    private static double round(double v, int decimals) {
        return java.math.BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private record OrderSendResult(String orderId, String rejectionReason, double sentPrice, double sentQty) {}

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(l -> CompletableFuture.supplyAsync(() -> {
                    var result = sendOrder(l.pair(), l.direction().toLowerCase(), l.orderType(), l.price(), l.quantity());
                    return new LegResult(l.legIndex(), l.pair(), l.direction(),
                        result.sentPrice(), result.sentQty(), result.orderId() != null, result.orderId(), result.rejectionReason());
                }))
                .toList();
            return futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(LegResult::legIndex)).toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }

    private OrderSendResult sendOrder(String pair, String side, String orderType, double price, double qty) {
        var isMarket = "MARKET".equals(orderType);
        var sentPrice = isMarket ? price : roundToSignificantFigures(price, PRICE_SIG_FIGS, PRICE_MAX_DECIMALS);
        var sentQty   = round(qty, AMOUNT_DECIMALS);
        try {
            // BTCUSD → tBTCUSD, BTCUSDT → tBTCUST; buy = positive amount, sell = negative
            var symbol = toBfxSymbol(pair);
            var amount = "buy".equals(side) ? sentQty : -sentQty;
            var bodyNode = mapper.createObjectNode()
                .put("type",   isMarket ? "EXCHANGE MARKET" : "EXCHANGE LIMIT")
                .put("symbol", symbol)
                .put("amount", String.format("%." + AMOUNT_DECIMALS + "f", amount));
            if (!isMarket) bodyNode.put("price", String.format("%." + PRICE_MAX_DECIMALS + "f", sentPrice));
            var bodyStr = mapper.writeValueAsString(bodyNode);
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
            // Success: [mts, type, messageID, null, [[id, ...]]]
            // Error:   [mts, "n", null, null, [...], null, "ERROR", "<message>"]
            if (node.isArray() && node.size() >= 5) {
                var orders = node.get(4);
                if (orders.isArray() && !orders.isEmpty()) {
                    return new OrderSendResult(orders.get(0).get(0).asText(), null, sentPrice, sentQty);
                }
            }
            String msg = "Order rejected";
            if (node.isArray() && node.size() >= 8 && "ERROR".equals(node.get(6).asText())) {
                msg = node.get(7).asText(msg);
            }
            log.warn("[BITFINEX] sendOrder rejected: {}", node);
            var lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("precision") || lowerMsg.contains("decimal")) {
                reportError("Bitfinex rejected an order due to a price/amount precision error ("
                    + msg + ") on " + symbol
                    + ". Trading on Bitfinex has been stopped. Check the decimal precision "
                    + "configured for this pair in BitfinexOrderClient and correct it, then restart "
                    + "Bitfinex from Exchange Settings.");
            }
            return new OrderSendResult(null, msg, sentPrice, sentQty);
        } catch (Exception e) {
            log.error("[BITFINEX] sendOrder failed: {}", e.getMessage());
            return new OrderSendResult(null, e.getMessage(), sentPrice, sentQty);
        }
    }

    @Override
    public CancelResult cancelOrder(String txid, String pair) {
        try {
            var bodyStr = "{\"id\":" + txid + "}";
            var nonce   = String.valueOf(System.currentTimeMillis());
            var path    = "/v2/auth/w/order/cancel";
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
            if (node.isArray() && node.size() >= 7 && "ERROR".equals(node.get(6).asText())) {
                return new CancelResult(false, node.size() >= 8 ? node.get(7).asText("Cancel rejected") : "Cancel rejected");
            }
            return new CancelResult(true, null);
        } catch (Exception e) {
            return new CancelResult(false, e.getMessage());
        }
    }

    private static String hmac384(String secret, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA384");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
