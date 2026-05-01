package com.ib.arb.execution;

import com.ib.arb.alert.AlertService;
import com.ib.arb.analytics.AnalyticsService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.broker.KrakenOrderClient.LegResult;
import com.ib.arb.config.DashboardWebSocketHandler;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.Trade;
import com.ib.arb.model.TradeLeg;
import com.ib.arb.position.PositionService;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.risk.RiskService;
import com.ib.arb.scanner.ArbitrageEngine;
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
 *   <li>Skip if the open-order limit is reached.</li>
 *   <li>Ask {@link ArbitrageEngine} for the best current signal.</li>
 *   <li>Confirm sufficient exchange balance via {@link PositionService}.</li>
 *   <li>Run pre-trade risk checks via {@link RiskService}.</li>
 *   <li>Place orders — either simulated (log only) or live via {@link KrakenOrderClient}.</li>
 *   <li>Persist the {@link Trade} and its {@link TradeLeg}s.</li>
 *   <li>Broadcast a {@link DashboardSnapshot} to all connected WebSocket clients.</li>
 * </ol>
 *
 * <p>Counters ({@code detected}, {@code executed}, {@code missed}) are in-memory and reset
 * on restart. They are exposed via {@link #getStats()} for the dashboard and REST API.
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

    @Value("${arb.order-size-usd}")
    private double orderSizeUsd;

    @Value("${arb.max-open-orders}")
    private int maxOpenOrders;

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
     * <p>Pushed whether the cycle resulted in a fill, a miss, or an abort — the
     * frontend always receives an up-to-date snapshot. {@code recentTrades} contains
     * only top-level trade data; legs must be fetched separately via
     * {@code GET /api/trades/{id}}.
     *
     * @param dailyProfitAndLoss cumulative P&amp;L since midnight UTC
     * @param brokerConnected    {@code true} if API credentials are set (or simulation mode is on)
     * @param arbStats           in-memory counters since last restart
     * @param recentTrades       last 20 trades ordered by time descending
     * @param prices             current bid/ask snapshot for every configured pair
     */
    public record DashboardSnapshot(
        double dailyProfitAndLoss,
        boolean brokerConnected,
        ArbitrageStats arbStats,
        List<Trade> recentTrades,
        List<PriceSnapshot> prices
    ) {}

    /**
     * Attempts one arbitrage cycle. Returns immediately if the open-order limit is
     * reached or no profitable signal exists.
     *
     * <p>In simulation mode the broker is not called; orders are logged at INFO level
     * and the trade is recorded as {@code FILLED} with leg status {@code SIMULATED}.
     * In live mode, a partial fill (some legs succeed, some fail) results in a
     * {@code CANCELLED} trade with individual leg statuses recorded for audit.
     */
    public void attemptArbitrage() {
        if (broker.openOrderCount() >= maxOpenOrders) return;

        var signal = arbitrageEngine.scan();
        if (signal.isEmpty()) return;

        detected++;
        var s = signal.get();
        totalEdge += s.profit();

        var pair1 = s.config().getPair1();
        var spentCurrency = "A".equals(s.cycle())
            ? pair1.substring(3)
            : pair1.substring(0, 3);
        if (!positions.hasAvailableBalance(s.exchange(), spentCurrency, orderSizeUsd)) {
            missed++;
            return;
        }

        var riskResult = risk.check(orderSizeUsd);
        if (!riskResult.allowed()) {
            missed++;
            return;
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

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var estimatedPnl = filled ? s.profit() * orderSizeUsd : 0.0;

        var trade = new Trade();
        trade.setTime(LocalDateTime.now());
        trade.setDirection(s.cycle());
        trade.setSpread(s.profit());
        trade.setPnl(estimatedPnl);
        trade.setStatus(filled ? "FILLED" : "CANCELLED");
        trade.setLatencyMs(latencyMs);

        for (var lr : legResults) {
            var leg = new TradeLeg();
            leg.setLegIndex(lr.legIndex());
            leg.setPair(lr.pair());
            leg.setDirection(lr.direction());
            leg.setPrice(lr.price());
            leg.setVolume(lr.volume());
            leg.setStatus(broker.isSimulation() ? "SIMULATED" : lr.filled() ? "FILLED" : "FAILED");
            leg.setOrderId(lr.orderId());
            trade.addLeg(leg);
        }

        tradeRepo.save(trade);

        if (filled) {
            executed++;
            triangleConfigRepo.incrementStats(s.config().getId(), estimatedPnl);
            alerts.tradeFilled(s, estimatedPnl);
        } else {
            missed++;
        }

        wsHandler.broadcast(new DashboardSnapshot(
            analytics.dailyProfitAndLoss(),
            broker.isConnected(),
            getStats(),
            tradeRepo.findTop20ByOrderByTimeDesc(),
            arbitrageEngine.currentSnapshots()
        ));
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

    /**
     * Returns a snapshot of the current in-memory performance counters.
     *
     * @return current {@link ArbitrageStats}; {@code avgEdge} is {@code 0.0} if nothing
     *         has been detected yet
     */
    public ArbitrageStats getStats() {
        return new ArbitrageStats(detected, executed, missed, detected > 0 ? totalEdge / detected : 0.0);
    }
}
