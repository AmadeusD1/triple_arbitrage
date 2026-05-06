package com.ib.arb.execution;

import com.ib.arb.alert.AlertService;
import com.ib.arb.analytics.AnalyticsService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.broker.KrakenOrderClient.LegResult;
import com.ib.arb.config.DashboardWebSocketHandler;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.Trade;
import com.ib.arb.model.TradeLeg;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.position.PositionService;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.risk.RiskService;
import com.ib.arb.scanner.ArbitrageEngine;
import com.ib.arb.scanner.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orchestrates a single arbitrage cycle: scan → validate → execute → record → broadcast.
 *
 * <p>Called by {@code ArbitrageScheduler} on a fixed delay. The full cycle is:
 * <ol>
 *   <li>Skip if within the post-trade cooldown window or open-order limit is reached.</li>
 *   <li>Ask {@link ArbitrageEngine} for the best current signal.</li>
 *   <li>Confirm sufficient exchange balance via {@link PositionService}.</li>
 *   <li>Run pre-trade risk checks via {@link RiskService}.</li>
 *   <li>Place orders — either simulated (log only) or live via {@link KrakenOrderClient}.</li>
 *   <li>Persist the {@link Trade} and its {@link TradeLeg}s.</li>
 *   <li>Broadcast a {@link DashboardSnapshot} to all connected WebSocket clients.</li>
 * </ol>
 */
@Service
public class AutoTrader {

    private static final Logger log = LoggerFactory.getLogger(AutoTrader.class);

    private final ArbitrageEngine arbitrageEngine;
    private final PositionService positions;
    private final RiskService risk;
    private final KrakenOrderClient broker;
    private final TradeRepository tradeRepo;
    private final AlertService alerts;
    private final AnalyticsService analytics;
    private final DashboardWebSocketHandler wsHandler;
    private final TriangleConfigRepository triangleConfigRepo;

    private volatile long detected = 0;
    private volatile long executed = 0;
    private volatile long missed = 0;
    private volatile double totalEdge = 0;
    private volatile boolean executing = false;
    private volatile long lastTradeCompletedMs = 0;

    @Value("${arb.order-size-usd}")
    private double orderSizeUsd;

    @Value("${arb.max-open-orders}")
    private int maxOpenOrders;

    @Value("${arb.trade-cooldown-ms:10000}")
    private long tradeCooldownMs;

    public AutoTrader(ArbitrageEngine arbitrageEngine, PositionService positions,
                      RiskService risk, KrakenOrderClient broker,
                      TradeRepository tradeRepo, AlertService alerts,
                      AnalyticsService analytics, DashboardWebSocketHandler wsHandler,
                      TriangleConfigRepository triangleConfigRepo) {
        this.arbitrageEngine = arbitrageEngine;
        this.positions = positions;
        this.risk = risk;
        this.broker = broker;
        this.tradeRepo = tradeRepo;
        this.alerts = alerts;
        this.analytics = analytics;
        this.wsHandler = wsHandler;
        this.triangleConfigRepo = triangleConfigRepo;
    }

    /**
     * Payload broadcast to all WebSocket clients after each arbitrage cycle.
     *
     * @param dailyProfitAndLoss cumulative P&amp;L since midnight UTC
     * @param brokerConnected    {@code true} if API credentials are set (or simulation mode is on)
     * @param arbStats           in-memory counters since last restart
     * @param recentTrades       last 20 trades ordered by time descending
     * @param prices             current bid/ask snapshot for every configured pair
     * @param tradeInProgress    {@code true} while a live order combo is being placed
     */
    public record DashboardSnapshot(
        double dailyProfitAndLoss,
        boolean brokerConnected,
        ArbitrageStats arbStats,
        List<Trade> recentTrades,
        List<PriceSnapshot> prices,
        boolean tradeInProgress
    ) {}

    /**
     * Attempts one arbitrage cycle. Returns immediately if within the cooldown window,
     * the open-order limit is reached, or no profitable signal exists.
     *
     * <p>In simulation mode the broker is not called; orders are logged at INFO level
     * and the trade is recorded as {@code SIMULATION}. In live mode, a partial fill
     * results in a {@code CANCELLED} trade with individual leg statuses recorded for audit.
     */
    public void attemptArbitrage() {
        if (broker.openOrderCount() >= maxOpenOrders) {
            broadcast();
            return;
        }

        if (System.currentTimeMillis() - lastTradeCompletedMs < tradeCooldownMs) {
            broadcast();
            return;
        }

        var signal = arbitrageEngine.scanForOpportunities();
        if (signal.isEmpty()) {
            broadcast();
            return;
        }

        detected++;
        var s = signal.get();
        totalEdge += s.profit();

        if (!hasBalanceForAllLegs(s.exchange(), s.config(), s.cycle(), orderSizeUsd)) {
            missed++;
            broadcast();
            return;
        }

        var riskResult = risk.check(orderSizeUsd);
        if (!riskResult.allowed()) {
            missed++;
            broadcast();
            return;
        }

        var profitResult = risk.checkProfit(
            s.config().getMinProfitPercent(), s.config().getMinProfitUsd(),
            s.profit(), s.profit() * orderSizeUsd);
        if (!profitResult.allowed()) {
            missed++;
            broadcast();
            return;
        }

        // Signal trade start — broadcast before placing orders so frontend shows the indicator
        if (!broker.isSimulation()) {
            executing = true;
            broadcast();
        }

        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = broker.computeLegs(s, orderSizeUsd);
            log.info("[SIM] Cycle {} | {} | profit={}", s.cycle(),
                legResults.stream()
                    .map(l -> l.direction() + " " + l.pair())
                    .reduce((a, b) -> a + ", " + b).orElse(""),
                String.format("%.5f", s.profit()));
        } else {
            legResults = broker.placeComboOrder(s, orderSizeUsd);
        }
        var latencyMs = System.currentTimeMillis() - start;

        executing = false;
        lastTradeCompletedMs = System.currentTimeMillis();

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var estimatedPnl = filled ? s.profit() * orderSizeUsd : 0.0;

        var trade = buildTrade(s, legResults, latencyMs, estimatedPnl, filled);
        tradeRepo.save(trade);

        if (filled) {
            executed++;
            triangleConfigRepo.incrementStats(s.config().getId(), estimatedPnl);
            alerts.tradeFilled(s, estimatedPnl);
        } else {
            missed++;
        }

        broadcast();
    }

    /**
     * Executes a trade immediately for the given triangle and cycle, bypassing the
     * cooldown. Open-order limit, balance, and risk checks still apply.
     */
    public ManualTradeResult executeTrade(TriangleConfig config, String cycle,
                                          List<KrakenOrderClient.ManualLeg> legs) {
        if (broker.openOrderCount() >= maxOpenOrders)
            return new ManualTradeResult(-1, "REJECTED_OPEN_ORDERS", 0.0);

        var exchange = Exchange.valueOf(config.getExchange().toUpperCase());
        // Derive notional size from leg 1 (price × volume) for balance and risk checks
        var leg1 = legs.get(0);
        var leg2 = legs.get(1);
        var leg3 = legs.get(2);
        var notional = leg1.price() * leg1.volume();
        var edge = "A".equals(cycle)
            ? leg1.price() * leg2.price() - leg3.price()
            : leg3.price() - leg1.price() * leg2.price();
        if (!hasBalanceForAllLegs(exchange, config, cycle, notional))
            return new ManualTradeResult(-1, "REJECTED_BALANCE", 0.0);

        var riskResult = risk.check(notional);
        if (!riskResult.allowed())
            return new ManualTradeResult(-1, "REJECTED_RISK", 0.0);

        var profitResult = risk.checkProfit(
            config.getMinProfitPercent(), config.getMinProfitUsd(),
            edge, edge * notional);
        if (!profitResult.allowed())
            return new ManualTradeResult(-1, "REJECTED_PROFIT", 0.0);

        if (!broker.isSimulation()) { executing = true; broadcast(); }

        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = legs.stream()
                .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(),
                    l.price(), l.volume(), true, null))
                .toList();
        } else {
            legResults = broker.placeSpecificLegs(legs);
        }
        var latencyMs = System.currentTimeMillis() - start;

        executing = false;
        lastTradeCompletedMs = System.currentTimeMillis();

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var estimatedPnl = filled ? edge * notional : 0.0;
        var signal = new Signal(exchange, config, cycle, edge);
        var trade = buildTrade(signal, legResults, latencyMs, estimatedPnl, filled);
        tradeRepo.save(trade);

        if (filled) { executed++; triangleConfigRepo.incrementStats(config.getId(), estimatedPnl); }
        else missed++;

        broadcast();
        return new ManualTradeResult(trade.getId(), trade.getStatus(), estimatedPnl);
    }

    public record ManualTradeResult(long tradeId, String status, double pnl) {}

    private boolean hasBalanceForAllLegs(Exchange exchange, TriangleConfig config,
                                          String cycle, double orderSize) {
        var cycleA = "A".equals(cycle);
        var pairs = new String[]{ config.getPair1(), config.getPair2(), config.getPair3() };
        var dirs  = cycleA
            ? new String[]{ "BUY", "BUY", "SELL" }
            : new String[]{ "SELL", "SELL", "BUY" };

        var snapshots = arbitrageEngine.currentSnapshots();

        for (int i = 0; i < 3; i++) {
            var pair  = pairs[i];
            var isBuy = "BUY".equals(dirs[i]);
            var ccy   = isBuy ? pair.substring(3) : pair.substring(0, 3);

            var price = snapshots.stream()
                .filter(p -> exchange.name().equals(p.exchange()) && pair.equals(p.pair()))
                .findFirst()
                .map(snap -> isBuy ? snap.ask() : snap.bid())
                .orElse(0.0);

            if (price == 0.0) return false;

            var required = isBuy ? orderSize : orderSize / price;

            if (!positions.hasAvailableBalance(exchange, ccy, required)) return false;
        }
        return true;
    }

    private Trade buildTrade(Signal signal, List<LegResult> legResults,
                             long latencyMs, double estimatedPnl, boolean filled) {
        var trade = new Trade()
            .setTime(LocalDateTime.now())
            .setDirection(signal.cycle())
            .setSpread(signal.profit())
            .setPnl(estimatedPnl)
            .setStatus(broker.isSimulation() ? "SIMULATION" : filled ? "FILLED" : "CANCELLED")
            .setLatencyMs(latencyMs);

        legResults.forEach(lr -> trade.addLeg(new TradeLeg()
            .setLegIndex(lr.legIndex())
            .setPair(lr.pair())
            .setDirection(lr.direction())
            .setPrice(lr.price())
            .setVolume(lr.volume())
            .setStatus(broker.isSimulation() ? "SIMULATED" : lr.filled() ? "FILLED" : "FAILED")
            .setOrderId(lr.orderId())));
        return trade;
    }

    /**
     * Cumulative performance counters since the last application restart.
     *
     * @param detected number of signals that exceeded the edge threshold
     * @param executed number of trades where all legs were filled
     * @param missed   number of cycles aborted due to balance, risk, or order failure
     * @param avgEdge  mean profit edge across all detected signals
     */
    public record ArbitrageStats(long detected, long executed, long missed, double avgEdge) {}

    public ArbitrageStats getStats() {
        return new ArbitrageStats(detected, executed, missed, detected > 0 ? totalEdge / detected : 0.0);
    }

    public void broadcast() {
        wsHandler.broadcast(new DashboardSnapshot(
            analytics.dailyProfitAndLoss(),
            broker.isConnected(),
            getStats(),
            tradeRepo.findTop20ByOrderByTimeDesc(),
            arbitrageEngine.currentSnapshots(),
            executing
        ));
    }
}
