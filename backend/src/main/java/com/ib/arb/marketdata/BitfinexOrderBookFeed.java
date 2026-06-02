package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bitfinex WS v2 order book feed.
 * Subscribes to {@code book} channel (R0 raw or P0 precision) for each pair.
 * Pair format: internal {@code BTCUSD} → Bitfinex symbol {@code tBTCUSD}.
 * Channels are tracked by chanId for correct routing of array-format messages.
 */
@Component
public class BitfinexOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(BitfinexOrderBookFeed.class);
    private static final String DEFAULT_WS = "wss://api.bitfinex.com/ws/2";

    private final ExchangeConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots    = new ConcurrentHashMap<>();
    private final Map<Integer, String>   chanToPair   = new ConcurrentHashMap<>(); // chanId → internal pair
    private final Map<String, double[]>  bestBid      = new ConcurrentHashMap<>();
    private final Map<String, double[]>  bestAsk      = new ConcurrentHashMap<>();
    private final AtomicInteger          subCount     = new AtomicInteger(0);

    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();
    private volatile WebSocket activeWs = null;

    public BitfinexOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BITFINEX; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    private String wsUrl() {
        return configRepo.findByExchange("BITFINEX")
            .map(c -> c.getWsUrl() != null && !c.getWsUrl().isBlank() ? c.getWsUrl() : DEFAULT_WS)
            .orElse(DEFAULT_WS);
    }

    /** Internal pair BTCUSD → tBTCUSD for Bitfinex */
    private static String toBfxSymbol(String pair) { return "t" + pair.toUpperCase(); }

    private void connect() {
        chanToPair.clear();
        bestBid.clear();
        bestAsk.clear();
        subCount.set(0);
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
            var root = mapper.readTree(json);

            // Object messages: info, subscribed, error
            if (root.isObject()) {
                var event = root.path("event").asText();
                if ("subscribed".equals(event) && "book".equals(root.path("channel").asText())) {
                    var chanId = root.path("chanId").asInt();
                    var sym    = root.path("symbol").asText(); // tBTCUSD
                    var pair   = sym.startsWith("t") ? sym.substring(1) : sym;
                    chanToPair.put(chanId, pair.toUpperCase());
                }
                return;
            }

            // Array messages: [chanId, ...data...]
            if (!root.isArray() || root.size() < 2) return;
            var chanId = root.get(0).asInt();
            var pair   = chanToPair.get(chanId);
            if (pair == null) return;

            var second = root.get(1);
            // Heartbeat
            if (second.isTextual() && "hb".equals(second.asText())) return;

            // Snapshot: [[price, count, amount], ...]
            if (second.isArray() && !second.isEmpty() && second.get(0).isArray()) {
                double bid = 0, bidQty = 0, ask = 0, askQty = 0;
                for (JsonNode entry : second) {
                    var price  = entry.get(0).asDouble();
                    var count  = entry.get(1).asInt();
                    var amount = entry.get(2).asDouble();
                    if (count > 0) {
                        if (amount > 0 && (bid == 0 || price > bid)) { bid = price; bidQty = amount; }
                        if (amount < 0 && (ask == 0 || price < ask)) { ask = price; askQty = -amount; }
                    }
                }
                if (bid > 0) bestBid.put(pair, new double[]{bid, bidQty});
                if (ask > 0) bestAsk.put(pair, new double[]{ask, askQty});
                publishIfReady(pair);
                return;
            }

            // Update: [price, count, amount]
            if (second.isArray() && second.size() == 3) {
                var price  = second.get(0).asDouble();
                var count  = second.get(1).asInt();
                var amount = second.get(2).asDouble();
                if (count > 0) {
                    if (amount > 0) {
                        var cur = bestBid.get(pair);
                        if (cur == null || price >= cur[0]) bestBid.put(pair, new double[]{price, amount});
                    } else if (amount < 0) {
                        var cur = bestAsk.get(pair);
                        if (cur == null || price <= cur[0]) bestAsk.put(pair, new double[]{price, -amount});
                    }
                }
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

    private String buildSubscribe(String pair) {
        try {
            var msg = mapper.createObjectNode()
                .put("event",   "subscribe")
                .put("channel", "book")
                .put("symbol",  toBfxSymbol(pair))
                .put("prec",    "P0")
                .put("freq",    "F0")
                .put("len",     "1");
            return mapper.writeValueAsString(msg);
        } catch (Exception e) { return "{}"; }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs  = ws;
            log.info("[BITFINEX] WebSocket connected — subscribing {} pair(s)", subscribedPairs.size());
            subscribedPairs.forEach(pair -> ws.sendText(buildSubscribe(pair), true));
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
            log.warn("[BITFINEX] WS error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
