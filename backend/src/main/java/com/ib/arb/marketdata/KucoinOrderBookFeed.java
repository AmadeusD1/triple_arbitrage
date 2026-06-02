package com.ib.arb.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.repository.ExchangeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * KuCoin order book feed.
 * KuCoin requires a REST call to obtain a dynamic WebSocket token and endpoint.
 * Subscribes to {@code /market/ticker:{symbol}} for each pair.
 * Pair: internal {@code BTCUSDT} → KuCoin symbol {@code BTC-USDT}.
 */
@Component
public class KucoinOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(KucoinOrderBookFeed.class);
    private static final String REST_URL = "https://api.kucoin.com";

    private final ExchangeConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService pingScheduler      = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile List<String> subscribedPairs = List.of();
    private volatile WebSocket activeWs = null;

    public KucoinOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.KUCOIN; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        connect();
    }

    /** Convert BTCUSDT → BTC-USDT for KuCoin */
    private static String toKcSymbol(String pair) {
        if (pair.endsWith("USDT") && pair.length() > 4) return pair.substring(0, pair.length() - 4) + "-USDT";
        if (pair.endsWith("BTC")  && pair.length() > 3) return pair.substring(0, pair.length() - 3) + "-BTC";
        if (pair.endsWith("ETH")  && pair.length() > 3) return pair.substring(0, pair.length() - 3) + "-ETH";
        if (pair.endsWith("USDC") && pair.length() > 4) return pair.substring(0, pair.length() - 4) + "-USDC";
        // Fallback: insert dash before last 3 chars
        return pair.length() > 3 ? pair.substring(0, pair.length() - 3) + "-" + pair.substring(pair.length() - 3) : pair;
    }

    private static String fromKcSymbol(String symbol) {
        return symbol.replace("-", "").toUpperCase();
    }

    private void connect() {
        try {
            // Fetch token & endpoint
            var tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(REST_URL + "/api/v1/bullet-public"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            var tokenResp = mapper.readTree(httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString()).body());
            var token     = tokenResp.path("data").path("token").asText();
            var server    = tokenResp.path("data").path("instanceServers").get(0);
            var endpoint  = server.path("endpoint").asText();
            var pingInterval = server.path("pingInterval").asLong(18000);

            var wsUrl = endpoint + "?token=" + token + "&connectId=" + UUID.randomUUID();
            httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new Listener(pingInterval))
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
            if ("welcome".equals(type) || "ack".equals(type) || "pong".equals(type)) return;
            if (!"message".equals(type)) return;

            var topic = root.path("topic").asText(); // e.g. /market/ticker:BTC-USDT
            if (!topic.startsWith("/market/ticker:")) return;
            var kcSymbol = topic.substring("/market/ticker:".length());
            var pair     = fromKcSymbol(kcSymbol);

            var data   = root.path("data");
            var bid    = data.path("bestBid").asDouble();
            var bidQty = data.path("bestBidSize").asDouble();
            var ask    = data.path("bestAsk").asDouble();
            var askQty = data.path("bestAskSize").asDouble();

            if (bid > 0 && ask > 0) {
                snapshots.put(pair, new OrderBook(pair, bid, bidQty, ask, askQty));
            }
        } catch (Exception ignored) {}
    }

    private String buildSubscribe() {
        try {
            var topicList = subscribedPairs.stream()
                .map(p -> toKcSymbol(p))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
            return mapper.writeValueAsString(Map.of(
                "id",           UUID.randomUUID().toString(),
                "type",         "subscribe",
                "topic",        "/market/ticker:" + topicList,
                "privateChannel", false,
                "response",     true
            ));
        } catch (Exception e) { return "{}"; }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private final long pingIntervalMs;

        Listener(long pingIntervalMs) { this.pingIntervalMs = pingIntervalMs; }

        @Override
        public void onOpen(WebSocket ws) {
            connected = true;
            activeWs  = ws;
            log.info("[KUCOIN] WebSocket connected — subscribing {} pair(s)", subscribedPairs.size());
            ws.sendText(buildSubscribe(), true);
            // KuCoin requires periodic ping to keep connection alive
            pingScheduler.scheduleWithFixedDelay(
                () -> { if (connected && activeWs != null) {
                    try { activeWs.sendText("{\"id\":\"" + System.currentTimeMillis() + "\",\"type\":\"ping\"}", true); }
                    catch (Exception ignored) {}
                }},
                pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS
            );
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
            log.warn("[KUCOIN] WS error: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }
    }
}
