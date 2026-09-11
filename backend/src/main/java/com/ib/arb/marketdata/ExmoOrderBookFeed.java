package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EXMO market data via WebSocket.
 * wss://ws-api.exmo.com:443/v1/public — subscribe to spot/order_book_updates:{PAIR}.
 * First message per pair is a full snapshot; later messages are deltas (qty 0 removes
 * a price level) — merged into a local order book, same pattern as {@link KrakenOrderBookFeed}.
 */
@Component
public class ExmoOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(ExmoOrderBookFeed.class);
    private static final String WS_URL = "wss://ws-api.exmo.com:443/v1/public";

    // Longest-first so "USDT" matches before "USD", "USDC" before "USD", etc.
    private static final List<String> QUOTE_SUFFIXES = List.of(
        "USDT", "USDC", "EUR", "GBP", "TRY", "USD", "BTC", "ETH"
    );

    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();
    private volatile Runnable onUpdate = () -> {};
    // bids: highest price first; asks: lowest price first
    private final Map<String, TreeMap<Double, Double>> bidBooks = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Double, Double>> askBooks = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile boolean stopped = false;
    private volatile List<String> subscribedPairs = List.of();
    private volatile WebSocket activeWs = null;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicLong lastMessageTime = new AtomicLong(0);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    @Override public Exchange getExchange() { return Exchange.EXMO; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return connected; }
    @Override public void setOnUpdate(Runnable onUpdate) { this.onUpdate = onUpdate; }

    @Override
    public void subscribe(List<String> pairs) {
        stopped = false;
        this.subscribedPairs = pairs;
        connect();
        reconnectScheduler.scheduleAtFixedRate(this::watchdog, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void disconnect() {
        stopped = true;
        connected = false;
        connecting.set(false);
        var ws = activeWs;
        activeWs = null;
        if (ws != null) try { ws.abort(); } catch (Exception ignored) {}
        snapshots.clear();
        bidBooks.clear();
        askBooks.clear();
        log.info("[EXMO] Feed disconnected");
    }

    @Override
    public void invalidate(String pair) {
        var key = pair.toUpperCase();
        snapshots.remove(key);
        bidBooks.remove(key);
        askBooks.remove(key);
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        var exmoPairs = subscribedPairs.stream().map(ExmoOrderBookFeed::toExmoSymbol).toList();

        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new Listener(exmoPairs))
                .whenComplete((ws, ex) -> {
                    connecting.set(false);
                    if (ex != null) scheduleReconnect();
                });
        } catch (Exception e) {
            connecting.set(false);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected = false;
        if (!stopped) reconnectScheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
    }

    private void watchdog() {
        if (stopped) return;
        var ws = activeWs;
        if (connected && ws != null) {
            long elapsed = System.currentTimeMillis() - lastMessageTime.get();
            if (lastMessageTime.get() > 0 && elapsed > 60_000) {
                log.warn("[EXMO] No messages for {}s — reconnecting", elapsed / 1000);
                try { ws.abort(); } catch (Exception ignored) {}
                scheduleReconnect();
            }
        }
    }

    private void handleMessage(String json) {
        try {
            lastMessageTime.set(System.currentTimeMillis());
            var node  = mapper.readTree(json);
            var event = node.path("event").asText();
            if (!"snapshot".equals(event) && !"update".equals(event)) return;

            var topic = node.path("topic").asText();
            var idx   = topic.indexOf(':');
            if (idx < 0) return;
            var pair = toPair(topic.substring(idx + 1));
            var data = node.path("data");

            if ("snapshot".equals(event)) {
                var bidBook = new TreeMap<Double, Double>(Comparator.reverseOrder());
                var askBook = new TreeMap<Double, Double>();
                applyLevels(data.path("bid"), bidBook);
                applyLevels(data.path("ask"), askBook);
                bidBooks.put(pair, bidBook);
                askBooks.put(pair, askBook);
            } else {
                var bidBook = bidBooks.computeIfAbsent(pair, k -> new TreeMap<>(Comparator.reverseOrder()));
                var askBook = askBooks.computeIfAbsent(pair, k -> new TreeMap<>());
                applyLevels(data.path("bid"), bidBook);
                applyLevels(data.path("ask"), askBook);
            }

            var bidBook = bidBooks.get(pair);
            var askBook = askBooks.get(pair);
            if (bidBook == null || askBook == null || bidBook.isEmpty() || askBook.isEmpty()) return;

            var bestBid = bidBook.firstEntry();
            var bestAsk = askBook.firstEntry();
            var ob = new OrderBook(pair, bestBid.getKey(), bestBid.getValue(), bestAsk.getKey(), bestAsk.getValue());
            if (ob.isValid()) { snapshots.put(pair, ob); onUpdate.run(); }
            // crossed books are transient during fast updates — keep last valid snapshot
        } catch (Exception ignored) {}
    }

    private void applyLevels(JsonNode array, TreeMap<Double, Double> book) {
        if (!array.isArray()) return;
        for (var level : array) {
            double price = level.path(0).asDouble();
            double qty   = level.path(1).asDouble();
            if (price <= 0) continue;
            if (qty == 0) book.remove(price);
            else book.put(price, qty);
        }
    }

    private String buildSubscribeMessage(List<String> exmoPairs) {
        try {
            var msg = mapper.createObjectNode();
            msg.put("method", "subscribe");
            var arr = msg.putArray("topics");
            exmoPairs.forEach(p -> arr.add("spot/order_book_updates:" + p));
            msg.put("id", 1);
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Convert BTCUSDT -> BTC_USDT for EXMO */
    static String toExmoSymbol(String pair) {
        var norm = pair.toUpperCase();
        for (var q : QUOTE_SUFFIXES) {
            if (norm.endsWith(q) && norm.length() > q.length())
                return norm.substring(0, norm.length() - q.length()) + "_" + q;
        }
        return norm;
    }

    // "BTC_USDT" -> "BTCUSDT"
    static String toPair(String exmoSymbol) {
        return exmoSymbol.replace("_", "").toUpperCase();
    }

    // ── inner listener ────────────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {
        private final List<String> exmoPairs;
        private final StringBuilder buffer = new StringBuilder();

        Listener(List<String> exmoPairs) { this.exmoPairs = exmoPairs; }

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs = ws;
            log.info("[EXMO] WebSocket connected — subscribing {} pair(s)", exmoPairs.size());
            ws.sendText(buildSubscribeMessage(exmoPairs), true);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleMessage(buffer.toString());
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("[EXMO] WebSocket error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("[EXMO] WebSocket closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }
    }
}
