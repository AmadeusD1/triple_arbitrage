package com.ib.arb.engine;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.OrderBookFeed;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of per-exchange arbitrage scan loops.
 *
 * <p>Each enabled exchange runs its own independent {@link ScheduledFuture} on a
 * dedicated thread-pool. Exchanges never share state — a trade on exchange A does not
 * affect exchange B's cooldown or open-order gate.
 *
 * <p>The global Start/Stop (Dashboard button) calls {@link #startAll()}/{@link #stopAll()}.
 * Per-exchange start/stop (Exchange Settings tab) calls {@link #startExchange}/{@link #stopExchange}.
 */
@Service
public class ExchangeManager {

    private static final Logger log = LoggerFactory.getLogger(ExchangeManager.class);

    @Value("${arb.scan-interval-ms:1000}")
    private long scanIntervalMs;

    private final AutoTrader autoTrader;
    private final List<OrderBookFeed> feeds;
    private final ExchangeConfigRepository configRepo;
    private final TriangleConfigRepository triangleRepo;

    private final ScheduledExecutorService executor =
        Executors.newScheduledThreadPool(16, r -> {
            var t = new Thread(r, "exchange-scan");
            t.setDaemon(true);
            return t;
        });

    private final Map<Exchange, ScheduledFuture<?>> scanFutures = new ConcurrentHashMap<>();
    private final AtomicBoolean globallyStarted = new AtomicBoolean(false);

    public ExchangeManager(AutoTrader autoTrader, List<OrderBookFeed> feeds,
                           ExchangeConfigRepository configRepo,
                           TriangleConfigRepository triangleRepo) {
        this.autoTrader   = autoTrader;
        this.feeds        = feeds;
        this.configRepo   = configRepo;
        this.triangleRepo = triangleRepo;
    }

    // ── Global start/stop (Dashboard button) ─────────────────────────────────

    public void startAll() {
        globallyStarted.set(true);
        enabledExchanges().forEach(this::startExchange);
        log.info("Global arbitrage start — {} enabled exchange(s)", enabledExchanges().size());
    }

    public void stopAll() {
        globallyStarted.set(false);
        new ArrayList<>(scanFutures.keySet()).forEach(this::stopExchange);
        log.info("Global arbitrage stop");
    }

    public boolean isGloballyRunning() { return globallyStarted.get(); }

    // ── Per-exchange start/stop (Exchange Settings tab) ───────────────────────

    public void startExchange(Exchange exchange) {
        if (scanFutures.containsKey(exchange)) {
            log.debug("[EM] {} already running", exchange);
            return;
        }
        subscribeFeeds(exchange);
        var future = executor.scheduleWithFixedDelay(
            () -> {
                try { autoTrader.attemptArbitrage(exchange); }
                catch (Exception e) { log.error("[EM] Scan error on {}", exchange, e); }
            },
            0, scanIntervalMs, TimeUnit.MILLISECONDS);
        scanFutures.put(exchange, future);
        log.info("[EM] Started scan loop for {}", exchange);
    }

    public void stopExchange(Exchange exchange) {
        var future = scanFutures.remove(exchange);
        if (future != null) {
            future.cancel(false);
            log.info("[EM] Stopped scan loop for {}", exchange);
        }
        feeds.stream()
            .filter(f -> f.getExchange() == exchange)
            .forEach(OrderBookFeed::disconnect);
    }

    public boolean isRunning(Exchange exchange) {
        return scanFutures.containsKey(exchange);
    }

    /**
     * Activates an exchange: subscribe its feed to its pairs, then start the scan
     * loop if the global running state is active.
     * Called by {@code ExchangeConfigController} when a config is created or enabled.
     */
    public void activateExchange(Exchange exchange) {
        subscribeFeeds(exchange);
        if (globallyStarted.get()) {
            startExchange(exchange);
        }
    }

    public void deactivateExchange(Exchange exchange) {
        stopExchange(exchange);
    }

    // ── Per-exchange running states (for status endpoint) ────────────────────

    public Map<String, Boolean> exchangeRunningStates() {
        var result = new ConcurrentHashMap<String, Boolean>();
        for (var exchange : Exchange.values()) {
            result.put(exchange.name(), scanFutures.containsKey(exchange));
        }
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Exchange> enabledExchanges() {
        return configRepo.findByEnabledTrue().stream()
            .map(c -> {
                try { return Exchange.valueOf(c.getExchange()); }
                catch (IllegalArgumentException e) {
                    log.warn("[EM] Unknown exchange in config: {}", c.getExchange());
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private void subscribeFeeds(Exchange exchange) {
        var pairs = triangleRepo.findAll().stream()
            .filter(t -> exchange.name().equalsIgnoreCase(t.getExchange()))
            .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
            .distinct()
            .toList();

        if (pairs.isEmpty()) {
            log.debug("[EM] No triangles configured for {} — skipping feed subscription", exchange);
            return;
        }

        feeds.stream()
            .filter(f -> f.getExchange() == exchange)
            .forEach(f -> {
                if (f.isConnected()) return;
                f.subscribe(pairs);
                log.info("[EM] Subscribed {} feed to {} pair(s)", exchange, pairs.size());
            });
    }
}
