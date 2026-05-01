package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Value;
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

@Component
public class KrakenOrderBookFeed implements OrderBookFeed {

    @Value("${kraken.ws-url:wss://ws.kraken.com/v2}")
    private String wsUrl;

    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();
    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();

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

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private void connect() {
        var krakenSymbols = subscribedPairs.stream()
            .map(KrakenOrderBookFeed::toKrakenSymbol)
            .toList();

        try {
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new Listener(krakenSymbols))
                .whenComplete((ws, ex) -> {
                    if (ex != null) scheduleReconnect();
                });
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected = false;
        reconnectScheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
    }

    private void handleMessage(String json) {
        try {
            var node = mapper.readTree(json);
            if (!"book".equals(node.path("channel").asText())) return;

            var type = node.path("type").asText();
            if (!"snapshot".equals(type) && !"update".equals(type)) return;

            for (var entry : node.path("data")) {
                var pair = toPair(entry.path("symbol").asText());
                var current = snapshots.get(pair);

                var bid = bestPrice(entry.path("bids"), current != null ? current.bid() : 0);
                var ask = bestPrice(entry.path("asks"), current != null ? current.ask() : 0);

                if (bid > 0 && ask > 0) {
                    snapshots.put(pair, new OrderBook(pair, bid, ask));
                }
            }
        } catch (Exception ignored) {}
    }

    private double bestPrice(JsonNode array, double fallback) {
        if (array.isArray() && !array.isEmpty()) {
            var price = array.get(0).path("price").asDouble();
            if (price > 0) return price;
        }
        return fallback;
    }

    private String buildSubscribeMessage(List<String> krakenSymbols) {
        try {
            var msg = mapper.createObjectNode();
            msg.put("method", "subscribe");
            var params = msg.putObject("params");
            params.put("channel", "book");
            params.put("depth", 1);
            var arr = (ArrayNode) params.putArray("symbol");
            krakenSymbols.forEach(arr::add);
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    // "EURUSD" → "EUR/USD"  (splits at position 3)
    public static String toKrakenSymbol(String pair) {
        return pair.substring(0, 3) + "/" + pair.substring(3);
    }

    // "EUR/USD" → "EURUSD"
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
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
