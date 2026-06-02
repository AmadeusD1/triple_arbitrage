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
 * Bitstamp order book feed.
 * Pair: internal {@code EURUSD} → channel {@code order_book_eurusd}.
 */
@Component
public class BitstampOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(BitstampOrderBookFeed.class);
    private static final String DEFAULT_WS = "wss://ws.bitstamp.net";

    private final ExchangeConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();
    private volatile WebSocket activeWs = null;

    public BitstampOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BITSTAMP; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    private String wsUrl() {
        return configRepo.findByExchange("BITSTAMP")
            .map(c -> c.getWsUrl() != null && !c.getWsUrl().isBlank() ? c.getWsUrl() : DEFAULT_WS)
            .orElse(DEFAULT_WS);
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
            var event = root.path("event").asText();
            if ("bts:subscription_succeeded".equals(event)) return;
            if (!"data".equals(event)) return;

            var channel = root.path("channel").asText(); // e.g. "order_book_eurusd"
            if (!channel.startsWith("order_book_")) return;
            var pairLower = channel.substring("order_book_".length()); // "eurusd"
            var pair      = pairLower.toUpperCase();                   // "EURUSD"

            var data   = root.path("data");
            var bids   = data.path("bids");
            var asks   = data.path("asks");

            if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) return;
            var bid    = bids.get(0).get(0).asDouble();
            var bidQty = bids.get(0).get(1).asDouble();
            var ask    = asks.get(0).get(0).asDouble();
            var askQty = asks.get(0).get(1).asDouble();

            if (bid > 0 && ask > 0) {
                snapshots.put(pair, new OrderBook(pair, bid, bidQty, ask, askQty));
            }
        } catch (Exception ignored) {}
    }

    private String buildSubscribe(String pair) {
        try {
            var msg = mapper.createObjectNode();
            msg.put("event", "bts:subscribe");
            msg.putObject("data").put("channel", "order_book_" + pair.toLowerCase());
            return mapper.writeValueAsString(msg);
        } catch (Exception e) { return "{}"; }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs = ws;
            log.info("[BITSTAMP] WebSocket connected — subscribing {} pair(s)", subscribedPairs.size());
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
            log.warn("[BITSTAMP] WS error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
