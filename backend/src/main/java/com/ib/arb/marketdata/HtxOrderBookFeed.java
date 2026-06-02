package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.repository.ExchangeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * HTX (formerly Huobi) order book feed.
 * HTX sends all WebSocket frames as gzip-compressed binary.
 * Subscribes to the BBO (best bid/offer) channel: {@code market.{symbol}.bbo}.
 * Pair: internal {@code BTCUSDT} → HTX symbol {@code btcusdt}.
 */
@Component
public class HtxOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(HtxOrderBookFeed.class);
    private static final String DEFAULT_WS = "wss://api.huobi.pro/ws";

    private final ExchangeConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();
    private volatile WebSocket activeWs = null;

    public HtxOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.HTX; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    private String wsUrl() {
        return configRepo.findByExchange("HTX")
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

    private String decompress(ByteBuffer buffer) throws Exception {
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try (var gz  = new GZIPInputStream(new ByteArrayInputStream(bytes));
             var out = new java.io.ByteArrayOutputStream()) {
            gz.transferTo(out);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void handleMessage(String json, WebSocket ws) {
        try {
            var root = mapper.readTree(json);

            // Heartbeat: {"ping": 12345}
            if (root.has("ping")) {
                ws.sendText("{\"pong\":" + root.path("ping").asLong() + "}", true);
                return;
            }

            // Subscription confirmation
            if (root.has("subbed")) return;

            var ch   = root.path("ch").asText(); // e.g. "market.btcusdt.bbo"
            if (!ch.contains(".bbo")) return;

            // ch = market.btcusdt.bbo → symbol = btcusdt
            var parts  = ch.split("\\.");
            var symbol = parts.length >= 2 ? parts[1].toUpperCase() : "";
            if (symbol.isEmpty()) return;

            var tick   = root.path("tick");
            var bid    = tick.path("bid").asDouble();
            var bidQty = tick.path("bidSize").asDouble();
            var ask    = tick.path("ask").asDouble();
            var askQty = tick.path("askSize").asDouble();

            if (bid > 0 && ask > 0) {
                snapshots.put(symbol, new OrderBook(symbol, bid, bidQty, ask, askQty));
            }
        } catch (Exception ignored) {}
    }

    private String buildSubscribe(String pair) {
        try {
            return mapper.writeValueAsString(Map.of(
                "sub", "market." + pair.toLowerCase() + ".bbo",
                "id",  "bbo_" + pair.toLowerCase()
            ));
        } catch (Exception e) { return "{}"; }
    }

    private class Listener implements WebSocket.Listener {
        private final java.io.ByteArrayOutputStream binaryBuf = new java.io.ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs  = ws;
            log.info("[HTX] WebSocket connected — subscribing {} pair(s)", subscribedPairs.size());
            subscribedPairs.forEach(pair -> ws.sendText(buildSubscribe(pair), true));
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            var bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryBuf.writeBytes(bytes);
            if (last) {
                try {
                    var buf = ByteBuffer.wrap(binaryBuf.toByteArray());
                    handleMessage(decompress(buf), ws);
                } catch (Exception ignored) {}
                binaryBuf.reset();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            // HTX may occasionally send text ping/pong
            if (last) handleMessage(data.toString(), ws);
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("[HTX] WS error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
