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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private volatile Runnable onUpdate = () -> {};
    private final Map<Integer, String>   chanToPair   = new ConcurrentHashMap<>(); // chanId → internal pair
    // bids: highest price first; asks: lowest price first - full depth-25 book per pair, not
    // just the top level, so a removed top-of-book entry correctly falls back to the next-best
    // price instead of leaving a stale/crossed value in place (see handleMessage).
    private final Map<String, TreeMap<Double, Double>> bidBooks = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Double, Double>> askBooks = new ConcurrentHashMap<>();
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
    @Override public void setOnUpdate(Runnable onUpdate) { this.onUpdate = onUpdate; }

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

    // Longest-first so "USDT" matches before "USD", "USDC" before "USD", etc.
    // Needed to correctly split a concatenated internal pair like "BTCUSDT" into
    // base/quote (a plain first-3-chars split mangles 4+ letter currencies).
    private static final List<String> QUOTE_SUFFIXES = List.of(
        "USDT", "USDC", "BUSD", "EUR", "GBP", "JPY", "TRY", "USD", "BTC", "ETH"
    );

    private static String[] splitPair(String pair) {
        var norm = pair.toUpperCase();
        for (var q : QUOTE_SUFFIXES) {
            if (norm.endsWith(q) && norm.length() > q.length())
                return new String[]{ norm.substring(0, norm.length() - q.length()), q };
        }
        return new String[]{ norm.substring(0, 3), norm.substring(3) };
    }

    // Bitfinex uses its own 3-letter codes for USDT ("UST") and USDC ("UDC") so that
    // common pairs stay in the compact fixed-width BASEQUOTE form (e.g. "USTUSD",
    // "UDCUSD", "BTCUST"). Any other currency longer than 3 letters (DOGE, AAVE, ...)
    // instead uses a colon-separated symbol (e.g. "DOGE:USD", "AAVE:USD").
    private static String toBfxCode(String ccy) {
        return switch (ccy) {
            case "USDT" -> "UST";
            case "USDC" -> "UDC";
            default -> ccy;
        };
    }

    private static String fromBfxCode(String code) {
        return switch (code) {
            case "UST" -> "USDT";
            case "UDC" -> "USDC";
            default -> code;
        };
    }

    /**
     * Internal pair BTCUSD → tBTCUSD, BTCUSDT → tBTCUST, DOGEUSD → tDOGE:USD for
     * Bitfinex (colon needed once either code exceeds 3 letters, to keep the
     * symbol unambiguous).
     */
    private static String toBfxSymbol(String pair) {
        var parts = splitPair(pair);
        var base = toBfxCode(parts[0]);
        var quote = toBfxCode(parts[1]);
        var separator = (base.length() == 3 && quote.length() == 3) ? "" : ":";
        return "t" + base + separator + quote;
    }

    /** Reverses {@link #toBfxSymbol}: tBTCUST -> BTCUSDT, tDOGE:USD -> DOGEUSD. */
    private static String fromBfxSymbol(String symbol) {
        var body = symbol.startsWith("t") ? symbol.substring(1) : symbol;
        String base, quote;
        if (body.contains(":")) {
            var parts = body.split(":", 2);
            base = parts[0];
            quote = parts[1];
        } else {
            base = body.substring(0, 3);
            quote = body.substring(3);
        }
        return fromBfxCode(base) + fromBfxCode(quote);
    }

    private void connect() {
        chanToPair.clear();
        bidBooks.clear();
        askBooks.clear();
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
                    var sym    = root.path("symbol").asText(); // tBTCUST, tDOGE:USD, ...
                    var pair   = fromBfxSymbol(sym);
                    chanToPair.put(chanId, pair);
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

            // Snapshot: [[price, count, amount], ...] - full book replace
            if (second.isArray() && !second.isEmpty() && second.get(0).isArray()) {
                var bidBook = new TreeMap<Double, Double>(Comparator.reverseOrder());
                var askBook = new TreeMap<Double, Double>();
                for (JsonNode entry : second) applyLevel(entry, bidBook, askBook);
                bidBooks.put(pair, bidBook);
                askBooks.put(pair, askBook);
                publishIfReady(pair);
                return;
            }

            // Update: [price, count, amount] - single-level upsert/remove against the
            // existing book, never a blind "only move one way" overwrite (that ratchets
            // the tracked spread until it crosses and gets stuck).
            if (second.isArray() && second.size() == 3) {
                var bidBook = bidBooks.computeIfAbsent(pair, k -> new TreeMap<>(Comparator.reverseOrder()));
                var askBook = askBooks.computeIfAbsent(pair, k -> new TreeMap<>());
                applyLevel(second, bidBook, askBook);
                publishIfReady(pair);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Applies one Bitfinex book level [price, count, amount] to the relevant side's book:
     * count == 0 removes that price level, count > 0 upserts it. amount's sign selects the
     * side (positive = bid, negative = ask) and its magnitude is the quantity.
     */
    private void applyLevel(JsonNode level, TreeMap<Double, Double> bidBook, TreeMap<Double, Double> askBook) {
        var price  = level.get(0).asDouble();
        var count  = level.get(1).asInt();
        var amount = level.get(2).asDouble();
        if (price <= 0) return;
        var book = amount >= 0 ? bidBook : askBook;
        if (count == 0) book.remove(price);
        else book.put(price, Math.abs(amount));
    }

    private void publishIfReady(String pair) {
        var bidBook = bidBooks.get(pair);
        var askBook = askBooks.get(pair);
        if (bidBook == null || askBook == null) return;
        var bestBid = bidBook.firstEntry();
        var bestAsk = askBook.firstEntry();
        if (bestBid == null || bestAsk == null) return;

        var ob = new OrderBook(pair, bestBid.getKey(), bestBid.getValue(), bestAsk.getKey(), bestAsk.getValue());
        if (ob.isValid()) {
            snapshots.put(pair, ob);
            onUpdate.run();
        }
        // crossed/invalid books are transient during fast updates - keep the last valid snapshot
    }

    private String buildSubscribe(String pair) {
        try {
            var msg = mapper.createObjectNode()
                .put("event",   "subscribe")
                .put("channel", "book")
                .put("symbol",  toBfxSymbol(pair))
                .put("prec",    "P0")
                .put("freq",    "F0")
                .put("len",     "25");
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
