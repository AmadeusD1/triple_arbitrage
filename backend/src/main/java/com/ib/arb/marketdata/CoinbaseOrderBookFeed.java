package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.repository.ExchangeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coinbase Exchange (Advanced) order book feed using the public WebSocket.
 * Subscribes to the {@code level2} channel for each product (e.g. BTC-USD).
 * Pair format: internal {@code BTCUSD} → Coinbase product_id {@code BTC-USD}.
 */
@Component
public class CoinbaseOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseOrderBookFeed.class);
    private static final String DEFAULT_WS = "wss://advanced-trade-ws.coinbase.com";

    private final ExchangeConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();
    // best bids/asks accumulated from l2update events
    private final Map<String, double[]> bestBid = new ConcurrentHashMap<>();
    private final Map<String, double[]> bestAsk = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();

    public CoinbaseOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.COINBASE; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    private String wsUrl() {
        return configRepo.findByExchange("COINBASE")
            .map(c -> c.getWsUrl() != null && !c.getWsUrl().isBlank() ? c.getWsUrl() : DEFAULT_WS)
            .orElse(DEFAULT_WS);
    }

    /** Convert internal pair (e.g. BTCUSD) to Coinbase product_id (BTC-USD). */
    private static String toProductId(String pair) {
        if (pair.length() < 4) return pair;
        var base  = pair.substring(0, pair.length() - 3);
        var quote = pair.substring(pair.length() - 3);
        return base + "-" + quote;
    }

    private static String fromProductId(String productId) {
        return productId.replace("-", "").toUpperCase();
    }

    private void connect() {
        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl()), new Listener())
                .whenComplete((ws, ex) -> { if (ex != null) scheduleReconnect(); });
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected = false;
        reconnectScheduler.schedule(this::connect, 3, TimeUnit.SECONDS);
    }

    private void handleMessage(String json) {
        try {
            var root  = mapper.readTree(json);
            var type  = root.path("type").asText();

            if ("subscriptions".equals(type)) return;

            if ("snapshot".equals(type)) {
                var productId = root.path("product_id").asText();
                var pair      = fromProductId(productId);
                var bids = root.path("bids");
                var asks = root.path("asks");
                if (bids.isArray() && !bids.isEmpty()) {
                    var b = bids.get(0);
                    bestBid.put(pair, new double[]{b.get(0).asDouble(), b.get(1).asDouble()});
                }
                if (asks.isArray() && !asks.isEmpty()) {
                    var a = asks.get(0);
                    bestAsk.put(pair, new double[]{a.get(0).asDouble(), a.get(1).asDouble()});
                }
                publishIfReady(pair);
            } else if ("l2update".equals(type)) {
                var productId = root.path("product_id").asText();
                var pair      = fromProductId(productId);
                root.path("changes").forEach(change -> {
                    var side  = change.get(0).asText();
                    var price = change.get(1).asDouble();
                    var size  = change.get(2).asDouble();
                    if ("buy".equals(side)) {
                        var cur = bestBid.get(pair);
                        if (cur == null || price >= cur[0]) {
                            if (size > 0) bestBid.put(pair, new double[]{price, size});
                        }
                    } else {
                        var cur = bestAsk.get(pair);
                        if (cur == null || price <= cur[0]) {
                            if (size > 0) bestAsk.put(pair, new double[]{price, size});
                        }
                    }
                });
                publishIfReady(pair);
            }
        } catch (Exception ignored) {}
    }

    private void publishIfReady(String pair) {
        var bid = bestBid.get(pair);
        var ask = bestAsk.get(pair);
        if (bid != null && ask != null && bid[0] > 0 && ask[0] > 0) {
            snapshots.put(pair, new OrderBook(pair, bid[0], bid[1], ask[0], ask[1]));
        }
    }

    private String buildSubscribe() {
        try {
            var msg = mapper.createObjectNode();
            msg.put("type", "subscribe");
            var ids = msg.putArray("product_ids");
            subscribedPairs.forEach(p -> ids.add(toProductId(p)));
            msg.putArray("channels").add("level2");
            return mapper.writeValueAsString(msg);
        } catch (Exception e) { return "{}"; }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            log.info("[COINBASE] WebSocket connected — subscribing {} pair(s)", subscribedPairs.size());
            ws.sendText(buildSubscribe(), true);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) { handleMessage(buf.toString()); buf.setLength(0); }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("[COINBASE] WS error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
