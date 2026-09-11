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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * BtcTurk market data via REST polling.
 * GET https://api.btcturk.com/api/v2/orderbook?pairSymbol=BTCUSDT — public, no auth required.
 * Fetches one order book per subscribed pair to get real top-of-book bid/ask quantities.
 */
@Component
public class BtcturkOrderBookFeed implements OrderBookFeed {

    private static final Logger log = LoggerFactory.getLogger(BtcturkOrderBookFeed.class);
    private static final String ORDERBOOK_URL = "https://api.btcturk.com/api/v2/orderbook?pairSymbol=";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OrderBook> snapshots = new ConcurrentHashMap<>();
    private volatile Runnable onUpdate = () -> {};

    // kept for Spring injection (used by BtcturkOrderClient)
    @SuppressWarnings("unused")
    private final ExchangeConfigRepository configRepo;

    private volatile boolean running = false;
    private volatile List<String> subscribedPairs = List.of();
    private ScheduledFuture<?> pollTask;
    private final Map<String, Double> lastBid = new ConcurrentHashMap<>();

    // Cloudflare (fronting BtcTurk's API) rate-limits this IP with HTTP 429 + a plain-text
    // "error code: 1015" body and a Retry-After header. Retrying every 2s regardless just
    // keeps hitting the ban, so on 429 the whole poll cycle pauses for the duration Cloudflare
    // asks for instead of hammering it.
    private volatile long pausedUntilMs = 0;

    public BtcturkOrderBookFeed(ExchangeConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override public Exchange getExchange() { return Exchange.BTCTURK; }
    @Override public OrderBook getSnapshot(String pair) { return snapshots.get(pair.toUpperCase()); }
    @Override public boolean isConnected() { return running; }
    @Override public void setOnUpdate(Runnable onUpdate) { this.onUpdate = onUpdate; }

    @Override
    public void subscribe(List<String> pairs) {
        this.subscribedPairs = pairs;
        if (!running) {
            running = true;
            pollTask = scheduler.scheduleAtFixedRate(this::poll, 0, 2, TimeUnit.SECONDS);
            log.info("[BTCTURK] REST polling started for {} pair(s): {}", pairs.size(), pairs);
        }
    }

    @Override
    public void invalidate(String pair) {
        var key = pair.toUpperCase();
        snapshots.remove(key);
        lastBid.remove(key);
    }

    @Override
    public void disconnect() {
        running = false;
        if (pollTask != null) pollTask.cancel(false);
        snapshots.clear();
        log.info("[BTCTURK] REST polling stopped");
    }

    private void poll() {
        if (System.currentTimeMillis() < pausedUntilMs) return;
        for (var pair : subscribedPairs) {
            try {
                var response = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(ORDERBOOK_URL + pair))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    var retryAfterSec = response.headers().firstValueAsLong("retry-after").orElse(30L);
                    pausedUntilMs = System.currentTimeMillis() + retryAfterSec * 1000;
                    log.warn("[BTCTURK] rate limited (429) — pausing polling for {}s", retryAfterSec);
                    return;
                }

                var data = mapper.readTree(response.body()).path("data");
                var bids = data.path("bids");
                var asks = data.path("asks");
                if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) continue;

                var bid    = bids.get(0).get(0).asDouble();
                var bidQty = bids.get(0).get(1).asDouble();
                var ask    = asks.get(0).get(0).asDouble();
                var askQty = asks.get(0).get(1).asDouble();
                if (bid <= 0 || ask <= 0) continue;

                var ob = new OrderBook(pair, bid, bidQty, ask, askQty);
                if (ob.isValid()) {
                    snapshots.put(pair, ob);
                    onUpdate.run();
                    if (!Double.valueOf(bid).equals(lastBid.put(pair, bid))) {
                        log.info("[BTCTURK] {} bid={} bidQty={} ask={} askQty={}", pair, bid, bidQty, ask, askQty);
                    }
                }
            } catch (Exception e) {
                log.warn("[BTCTURK] poll error for {}: {}", pair, e.getMessage());
            }
        }
    }
}
