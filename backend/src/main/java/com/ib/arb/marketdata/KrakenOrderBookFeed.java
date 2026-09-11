package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

@Component
public class KrakenOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(KrakenOrderBookFeed.class);

    @Value("${kraken.ws-url:wss://ws.kraken.com/v2}")
    private String wsUrl;

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

    @Override
    public Exchange getExchange() {
        return Exchange.KRAKEN;
    }

    @Override
    public OrderBook getSnapshot(String pair) {
        return snapshots.get(pair);
    }

    @Override public void setOnUpdate(Runnable onUpdate) { this.onUpdate = onUpdate; }

    @Override
    public void subscribe(List<String> pairs) {
        stopped = false;
        this.subscribedPairs = pairs;
        connect();
        reconnectScheduler.scheduleAtFixedRate(this::keepAlive, 30, 30, TimeUnit.SECONDS);
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
        log.info("[KRAKEN] Feed disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        var krakenSymbols = subscribedPairs.stream()
            .map(KrakenOrderBookFeed::toKrakenSymbol)
            .toList();

        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new Listener(krakenSymbols))
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

    @Override
    public void invalidate(String pair) {
        snapshots.remove(pair);
        bidBooks.remove(pair);
        askBooks.remove(pair);
    }

    private void touchSnapshots() {
        snapshots.replaceAll((p, ob) -> ob.touched());
    }

    private void keepAlive() {
        if (stopped) return;
        var ws = activeWs;
        if (connected && ws != null) {
            long elapsed = System.currentTimeMillis() - lastMessageTime.get();
            if (lastMessageTime.get() > 0 && elapsed > 60_000) {
                log.warn("[KRAKEN] No messages for {}s — reconnecting", elapsed / 1000);
                try { ws.abort(); } catch (Exception ignored) {}
                scheduleReconnect();
                return;
            }
            try { ws.sendText("{\"method\":\"ping\"}", true); } catch (Exception ignored) {}
        }
    }

    private void handleMessage(String json) {
        try {
            lastMessageTime.set(System.currentTimeMillis());
            var node = mapper.readTree(json);

            var channel = node.path("channel").asText();
            var method  = node.path("method").asText();
            if ("heartbeat".equals(channel) || "pong".equals(method)) {
                touchSnapshots();
                return;
            }

            if ("subscribe".equals(method)
                    && !node.path("success").asBoolean(true)) {
                log.warn("Kraken subscription failed — pair '{}': {}",
                    node.path("symbol").asText("unknown"),
                    node.path("error").asText("unknown error"));
                return;
            }

            if (!"book".equals(channel)) return;

            var type = node.path("type").asText();
            if (!"snapshot".equals(type) && !"update".equals(type)) return;

            for (var entry : node.path("data")) {
                var pair = toPair(entry.path("symbol").asText());

                if ("snapshot".equals(type)) {
                    var bidBook = new TreeMap<Double, Double>(Comparator.reverseOrder());
                    var askBook = new TreeMap<Double, Double>();
                    applyLevels(entry.path("bids"), bidBook);
                    applyLevels(entry.path("asks"), askBook);
                    bidBooks.put(pair, bidBook);
                    askBooks.put(pair, askBook);
                } else {
                    var bidBook = bidBooks.computeIfAbsent(pair, k -> new TreeMap<>(Comparator.reverseOrder()));
                    var askBook = askBooks.computeIfAbsent(pair, k -> new TreeMap<>());
                    applyLevels(entry.path("bids"), bidBook);
                    applyLevels(entry.path("asks"), askBook);
                }

                var bidBook = bidBooks.get(pair);
                var askBook = askBooks.get(pair);
                if (bidBook == null || askBook == null) continue;

                var bestBid = bidBook.firstEntry();
                var bestAsk = askBook.firstEntry();
                if (bestBid != null && bestAsk != null) {
                    var ob = new OrderBook(pair, bestBid.getKey(), bestBid.getValue(), bestAsk.getKey(), bestAsk.getValue());
                    if (ob.isValid()) {
                        snapshots.put(pair, ob);
                        onUpdate.run();
                    }
                    // crossed books are transient during fast updates — keep last valid snapshot
                }
            }
        } catch (Exception ignored) {}
    }

    private void applyLevels(JsonNode array, TreeMap<Double, Double> book) {
        if (!array.isArray()) return;
        for (var level : array) {
            double price = level.path("price").asDouble();
            double qty   = level.path("qty").asDouble();
            if (price <= 0) continue;
            if (qty == 0) book.remove(price);
            else book.put(price, qty);
        }
    }

    private String buildSubscribeMessage(List<String> krakenSymbols) {
        try {
            var msg = mapper.createObjectNode();
            msg.put("method", "subscribe");
            var params = msg.putObject("params");
            params.put("channel", "book");
            params.put("depth", 10);
            var arr = (ArrayNode) params.putArray("symbol");
            krakenSymbols.forEach(arr::add);
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    // Longest-first so "USDT" matches before "USD", "USDC" before "USD", etc.
    // Mirrors AutoTrader.QUOTE_SUFFIXES — needed because a plain first-3-chars split
    // mangles 4+ letter base currencies (e.g. "DOGEBTC" → "DOG/EBTC").
    private static final List<String> QUOTE_SUFFIXES = List.of(
        "USDT", "USDC", "BUSD", "EUR", "GBP", "JPY", "TRY", "USD", "BTC", "ETH"
    );

    // "BTCUSD" → "BTC/USD", "ETHBTC" → "ETH/BTC", "DOGEBTC" → "DOGE/BTC"
    // Kraken v2 WS API (wss://ws.kraken.com/v2) uses BTC, not XBT.
    public static String toKrakenSymbol(String pair) {
        var norm = pair.toUpperCase();
        for (var q : QUOTE_SUFFIXES) {
            if (norm.endsWith(q) && norm.length() > q.length())
                return norm.substring(0, norm.length() - q.length()) + "/" + q;
        }
        return norm.substring(0, 3) + "/" + norm.substring(3);
    }

    // "BTC/USD" → "BTCUSD", "ETH/BTC" → "ETHBTC"
    static String toPair(String krakenSymbol) {
        return krakenSymbol.replace("/", "");
    }

    // ── inner listener ────────────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {

        private final List<String> krakenSymbols;
        private final StringBuilder buffer = new StringBuilder();

        Listener(List<String> krakenSymbols) {
            this.krakenSymbols = krakenSymbols;
        }

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs = ws;
            log.info("[KRAKEN] WebSocket connected");
            ws.sendText(buildSubscribeMessage(krakenSymbols), true);
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
            log.warn("[KRAKEN] WebSocket error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("[KRAKEN] WebSocket closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }
    }
}
