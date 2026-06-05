package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

@Component
public class BybitOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(BybitOrderBookFeed.class);
    private static final String WS_URL = "wss://stream.bybit.com/v5/public/spot";

    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Double, Double>> bidBooks = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Double, Double>> askBooks = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile boolean stopped = false;
    private volatile List<String> subscribedPairs = List.of();
    private volatile WebSocket activeWs = null;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public BybitOrderBookFeed() {
        // Ping every 20s to keep the connection alive
        scheduler.scheduleAtFixedRate(() -> {
            var ws = activeWs;
            if (connected && ws != null) {
                try { ws.sendText("{\"op\":\"ping\"}", true); } catch (Exception ignored) {}
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    @Override
    public Exchange getExchange() { return Exchange.BYBIT; }

    @Override
    public OrderBook getSnapshot(String pair) { return snapshots.get(pair); }

    @Override
    public void subscribe(List<String> pairs) {
        stopped = false;
        this.subscribedPairs = pairs;
        connect();
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
        log.info("[BYBIT] Feed disconnected");
    }

    @Override
    public boolean isConnected() { return connected; }

    private void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new Listener())
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
        activeWs = null;
        if (!stopped) scheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
    }

    private void handleMessage(String json) {
        try {
            var node = mapper.readTree(json);

            // Ignore op responses (subscribe acks, pong, etc.)
            if (node.has("op")) return;

            var topic = node.path("topic").asText();
            if (!topic.startsWith("orderbook.")) return;

            var type = node.path("type").asText();
            if (!"snapshot".equals(type) && !"delta".equals(type)) return;

            var data = node.path("data");
            var pair = toPair(data.path("s").asText());

            if ("snapshot".equals(type)) {
                var bidBook = new TreeMap<Double, Double>(Comparator.reverseOrder());
                var askBook = new TreeMap<Double, Double>();
                applyLevels(data.path("b"), bidBook);
                applyLevels(data.path("a"), askBook);
                bidBooks.put(pair, bidBook);
                askBooks.put(pair, askBook);
            } else {
                var bidBook = bidBooks.computeIfAbsent(pair, k -> new TreeMap<>(Comparator.reverseOrder()));
                var askBook = askBooks.computeIfAbsent(pair, k -> new TreeMap<>());
                applyLevels(data.path("b"), bidBook);
                applyLevels(data.path("a"), askBook);
            }

            var bidBook = bidBooks.get(pair);
            var askBook = askBooks.get(pair);
            if (bidBook == null || askBook == null) return;

            var bestBid = bidBook.firstEntry();
            var bestAsk = askBook.firstEntry();
            if (bestBid != null && bestAsk != null) {
                snapshots.put(pair, new OrderBook(pair,
                    bestBid.getKey(), bestBid.getValue(),
                    bestAsk.getKey(), bestAsk.getValue()));
            }
        } catch (Exception ignored) {}
    }

    // Bybit sends bids/asks as [["price", "qty"], ...]
    private void applyLevels(JsonNode array, TreeMap<Double, Double> book) {
        if (!array.isArray()) return;
        for (var level : array) {
            double price = level.get(0).asDouble();
            double qty   = level.get(1).asDouble();
            if (price <= 0) continue;
            if (qty == 0) book.remove(price);
            else book.put(price, qty);
        }
    }

    private String buildSubscribeMessage(List<String> pairs) {
        try {
            var msg = mapper.createObjectNode();
            msg.put("op", "subscribe");
            var args = (ArrayNode) msg.putArray("args");
            pairs.stream()
                 .map(p -> "orderbook.1." + toBybitSymbol(p))
                 .forEach(args::add);
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    // EURUSD → EURUSDT,  EURGBP → EURGBP (unchanged for cross pairs)
    static String toBybitSymbol(String pair) {
        if (pair.endsWith("USD")) return pair + "T";
        return pair;
    }

    // EURUSDT → EURUSD,  EURGBP → EURGBP
    static String toPair(String symbol) {
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 1);
        return symbol;
    }

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs = ws;
            log.info("[BYBIT] WebSocket connected");
            ws.sendText(buildSubscribeMessage(subscribedPairs), true);
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
            log.warn("[BYBIT] WebSocket error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("[BYBIT] WebSocket closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }
    }
}
