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
 * Binance order book feed using the {@code <symbol>@bookTicker} stream.
 * Pair format: internal {@code BTCUSDT} → Binance stream {@code btcusdt@bookTicker}.
 */
@Component
public class BinanceOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(BinanceOrderBookFeed.class);
    private static final String DEFAULT_WS = "wss://stream.binance.com:9443/ws";

    private final ExchangeConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();

    public BinanceOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BINANCE; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair); }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    private String wsUrl() {
        return configRepo.findByExchange("BINANCE")
            .map(c -> c.getWsUrl() != null && !c.getWsUrl().isBlank() ? c.getWsUrl() : DEFAULT_WS)
            .orElse(DEFAULT_WS);
    }

    private void connect() {
        // Build combined stream URL: /stream?streams=btcusdt@bookTicker/ethusdt@bookTicker
        var streams = subscribedPairs.stream()
            .map(p -> p.toLowerCase() + "@bookTicker")
            .reduce((a, b) -> a + "/" + b)
            .orElse("");
        if (streams.isEmpty()) return;

        var url = wsUrl().replace("/ws", "/stream") + "?streams=" + streams;
        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), new Listener())
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
            // Combined stream wraps data in {"stream":"...", "data":{...}}
            var data = root.has("data") ? root.path("data") : root;
            var symbol = data.path("s").asText();   // e.g. "BTCUSDT"
            var bid    = data.path("b").asDouble();
            var bidQty = data.path("B").asDouble();
            var ask    = data.path("a").asDouble();
            var askQty = data.path("A").asDouble();
            if (bid > 0 && ask > 0) {
                snapshots.put(symbol, new OrderBook(symbol, bid, bidQty, ask, askQty));
            }
        } catch (Exception ignored) {}
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            log.info("[BINANCE] WebSocket connected");
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
            log.warn("[BINANCE] WS error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
