package com.ib.arb.engine;

import com.ib.arb.alert.AlertService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.broker.KrakenOrderClient.LegResult;
import com.ib.arb.broker.OrderLeg;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.CurrencyRateFeed;
import com.ib.arb.marketdata.OrderBook;
import com.ib.arb.model.Trade;
import com.ib.arb.model.TradeLeg;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.position.PositionService;
import com.ib.arb.model.MissedOpportunity;
import com.ib.arb.repository.MissedOpportunityRepository;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.risk.RiskService;
import static com.ib.arb.common.Constants.Direction.BUY;
import static com.ib.arb.common.Constants.LegStatus.FAILED;
import static com.ib.arb.common.Constants.LegStatus.SIMULATED;
import static com.ib.arb.common.Constants.TradeStatus.CANCELLED;
import static com.ib.arb.common.Constants.TradeStatus.FILLED;
import static com.ib.arb.common.Constants.TradeStatus.SIMULATION;
import static com.ib.arb.common.Constants.RejectionStatus.REJECTED_BALANCE;
import static com.ib.arb.common.Constants.RejectionStatus.REJECTED_RISK;
import static com.ib.arb.common.Constants.RejectionStatus.REJECTED_PROFIT;
import com.ib.arb.scanner.Cycle;
import com.ib.arb.scanner.Signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 * Orchestrates arbitrage cycles (automated and manual): scan → validate →
 * execute → record → broadcast.
 *
 * <p>
 * Automated path (called by {@code ArbitrageScheduler}):
 * {@code attemptArbitrage()} guards + scans, then delegates to
 * {@code executeArbitrage(Signal)}.
 *
 * <p>
 * Manual path (called by {@code ArbitrageController}):
 * {@code executeTrade()} accepts caller-provided legs and bypasses the
 * cooldown.
 *
 * <p>
 * Both paths share {@code validatePreExecution()} and
 * {@code finalizeExecution()} helpers.
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
    private final TriangleConfigRepository triangleConfigRepo;
    private final CurrencyRateFeed currencyRateFeed;
    private final MissedOpportunityRepository missedOpportunityRepo;

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
            TriangleConfigRepository triangleConfigRepo, CurrencyRateFeed currencyRateFeed,
            MissedOpportunityRepository missedOpportunityRepo) {
        this.arbitrageEngine = arbitrageEngine;
        this.positions = positions;
        this.risk = risk;
        this.broker = broker;
        this.tradeRepo = tradeRepo;
        this.alerts = alerts;
        this.triangleConfigRepo = triangleConfigRepo;
        this.currencyRateFeed = currencyRateFeed;
        this.missedOpportunityRepo = missedOpportunityRepo;
    }

    // -------------------------------------------------------------------------
    // Automated path
    // -------------------------------------------------------------------------

    /**
     * Guards (cooldown + open-order limit) → scans for a signal → delegates to
     * {@link #executeArbitrage(Signal)}. Returns immediately (with a broadcast) on
     * any guard failure
     * or when no profitable signal is found.
     */
    public void attemptArbitrage() {
        if (broker.openOrderCount() >= maxOpenOrders) {
            log.debug("[ARB] Skipping — open order limit reached ({})", maxOpenOrders);
            return;
        }

        if (System.currentTimeMillis() - lastTradeCompletedMs < tradeCooldownMs) {
            log.debug("[ARB] Skipping — within cooldown window");
            return;
        }

        var signal = arbitrageEngine.scanForOpportunities();
        if (signal.isEmpty()) {
            log.debug("[ARB] No opportunity found this cycle");
            return;
        }

        executeArbitrage(signal.get());
    }

    /**
     * Executes the automated arbitrage path for a detected signal:
     * validate → place combo order → finalize.
     */
    private void executeArbitrage(Signal s) {
        detected++;
        totalEdge += s.profit();
        log.info("[ARB] Signal detected — triangle={} exchange={} cycle={} profit={}",
                s.config().getId(), s.exchange(), s.cycle(), String.format("%.5f", s.profit()));

        var maxVolume = calculateMaxVolume(s);
        log.info("[ARB] Liquidity cap — {}", String.format("%.2f", maxVolume));
        maxVolume = Math.min(orderSizeUsd, maxVolume);

        var legs = computeLegs(s, maxVolume);
        var expectedPnl = computePnlFromLegs(legs, maxVolume);

        var v = validatePreExecution(s.exchange(), s.config(), s.cycle().name(), maxVolume, s.profit(), expectedPnl);
        if (!v.allowed()) {
            switch (v.rejectionStatus()) {
                case REJECTED_BALANCE -> log.warn("[ARB] Missed — insufficient balance for triangle={} cycle={}",
                        s.config().getId(), s.cycle());
                case REJECTED_RISK -> log.warn("[ARB] Missed — risk check failed: {}", v.reason());
                case REJECTED_PROFIT -> log.warn("[ARB] Missed — profit threshold not met: {}", v.reason());
            }
            missedOpportunityRepo.save(new MissedOpportunity()
                    .setTime(LocalDateTime.now())
                    .setTriangleId(s.config().getId())
                    .setExchange(s.exchange().name())
                    .setPair1(s.config().getPair1())
                    .setPair2(s.config().getPair2())
                    .setPair3(s.config().getPair3())
                    .setCycle(s.cycle().name())
                    .setEdge(s.profit())
                    .setOrderSize(maxVolume)
                    .setRejection(v.rejectionStatus())
                    .setReason(v.reason())
                    .setExpectedPnl(expectedPnl)
                    .setLeg1Price(legs.get(0).price())
                    .setLeg1Volume(legs.get(0).quantity())
                    .setLeg2Price(legs.get(1).price())
                    .setLeg2Volume(legs.get(1).quantity())
                    .setLeg3Price(legs.get(2).price())
                    .setLeg3Volume(legs.get(2).quantity()));
            missed++;
            return;
        }

        log.info("[ARB] Placing orders — triangle={} cycle={} orderSize={} expectedPnl={}",
                s.config().getId(), s.cycle(), maxVolume, String.format("%.2f", expectedPnl));
        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = legs.stream()
                    .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(),
                            l.price(), l.quantity(), true, null))
                    .toList();
            log.info("[SIM] Cycle {} | {} | profit={}", s.cycle(),
                    legResults.stream()
                            .map(l -> l.direction() + " " + l.pair())
                            .reduce((a, b) -> a + ", " + b).orElse(""),
                    String.format("%.5f", s.profit()));
        } else {
            legResults = broker.placeOrderLegs(legs);
        }
        var latencyMs = System.currentTimeMillis() - start;

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var estimatedPnl = filled ? expectedPnl: 0.0;

        finalizeExecution(s, legResults, latencyMs, estimatedPnl, filled, "ARB", true, maxVolume, expectedPnl);
    }

    public double calculateMaxVolume(Signal s) {
        var pairs = new String[] { s.config().getPair1(), s.config().getPair2(), s.config().getPair3() };
        double minVolume = switch (s.cycle()) {
            case BBS -> min3(
                    s.b1().askQty() * s.b1().ask() * quoteRate(pairs[0]),
                    s.b2().askQty() * s.b2().ask() * quoteRate(pairs[1]),
                    s.b3().bidQty() * s.b3().bid() * quoteRate(pairs[2]));
            case BSS -> min3(
                    s.b1().askQty() * s.b1().ask() * quoteRate(pairs[0]),
                    s.b2().bidQty() * s.b2().bid() * quoteRate(pairs[1]),
                    s.b3().bidQty() * s.b3().bid() * quoteRate(pairs[2]));
            case BSB -> min3(
                    s.b1().askQty() * s.b1().ask() * quoteRate(pairs[0]),
                    s.b2().bidQty() * s.b2().bid() * quoteRate(pairs[1]),
                    s.b3().askQty() * s.b3().ask() * quoteRate(pairs[2]));
            case SBS -> min3(
                    s.b1().bidQty() * s.b1().bid() * quoteRate(pairs[0]),
                    s.b2().askQty() * s.b2().ask() * quoteRate(pairs[1]),
                    s.b3().bidQty() * s.b3().bid() * quoteRate(pairs[2]));
        };
        log.debug("[ARB] Minimum volume — volume={} (final volume={})",
                String.format("%.2f", minVolume), String.format("%.2f", minVolume));

        return minVolume;
    }

    // -------------------------------------------------------------------------
    // Manual path
    // -------------------------------------------------------------------------

    /**
     * Executes a trade immediately for the given triangle and cycle, bypassing the
     * cooldown. Open-order limit, balance, and risk checks still apply.
     */
    public ManualTradeResult executeTrade(TriangleConfig config, String cycle,
            List<OrderLeg> legs) {
        log.info("[MANUAL] executeTrade triangle={} exchange={} cycle={} legs={}",
                config.getId(), config.getExchange(), cycle, legs.size());

        if (broker.openOrderCount() >= maxOpenOrders) {
            log.warn("[MANUAL] Rejected — open order limit reached ({})", maxOpenOrders);
            return new ManualTradeResult(-1, "REJECTED_OPEN_ORDERS", 0.0);
        }

        var exchange = Exchange.valueOf(config.getExchange().toUpperCase());
        var cycleEnum = Cycle.valueOf(cycle);
        // Derive notional size from leg 1 (price × quantity) for balance and risk checks
        var notional = legs.get(0).price() * legs.get(0).quantity();
        var edge = switch (cycleEnum) {
            case BBS -> legs.get(0).price() * legs.get(1).price() - legs.get(2).price();
            case BSS -> legs.get(0).price() - legs.get(1).price() * legs.get(2).price();
            case BSB -> legs.get(0).price() * legs.get(2).price() - legs.get(1).price();
            case SBS -> legs.get(1).price() - legs.get(0).price() * legs.get(2).price();
        };

        log.info("[MANUAL] Computed edge — cycle={} notional={} edge={}",
                cycleEnum, String.format("%.2f", notional), String.format("%.5f", edge));

        var manualExpectedPnl = computePnlFromLegs(legs, notional);
        var v = validatePreExecution(exchange, config, cycle, notional, edge, manualExpectedPnl);
        if (!v.allowed()) {
            switch (v.rejectionStatus()) {
                case REJECTED_BALANCE -> log.warn("[MANUAL] Rejected — insufficient balance for triangle={} cycle={}",
                        config.getId(), cycle);
                case REJECTED_RISK -> log.warn("[MANUAL] Rejected — risk check failed: {}", v.reason());
                case REJECTED_PROFIT -> log.warn("[MANUAL] Rejected — profit threshold not met: {}", v.reason());
            }
            return new ManualTradeResult(-1, v.rejectionStatus(), 0.0);
        }

        if (!broker.isSimulation()) {
            executing = true;
        }

        log.info("[MANUAL] Placing orders — triangle={} cycle={} notional={} edge={}",
                config.getId(), cycle, String.format("%.2f", notional), String.format("%.5f", edge));
        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = legs.stream()
                    .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(),
                            l.price(), l.quantity(), true, null))
                    .toList();
        } else {
            legResults = broker.placeOrderLegs(legs);
        }
        var latencyMs = System.currentTimeMillis() - start;

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var estimatedPnl = filled ? edge * notional : 0.0;
        var b1 = new OrderBook(config.getPair1(), legs.get(0).price(), 0.0, legs.get(0).price(), 0.0);
        var b2 = new OrderBook(config.getPair2(), legs.get(1).price(), 0.0, legs.get(1).price(), 0.0);
        var b3 = new OrderBook(config.getPair3(), legs.get(2).price(), 0.0, legs.get(2).price(), 0.0);
        var signal = new Signal(exchange, config, cycleEnum, edge, b1, b2, b3);

        var trade = finalizeExecution(signal, legResults, latencyMs, estimatedPnl, filled, "MANUAL", false, notional, manualExpectedPnl);
        return new ManualTradeResult(trade.getId(), trade.getStatus(), estimatedPnl);
    }

    public record ManualTradeResult(long tradeId, String status, double pnl) {
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private record ValidationResult(boolean allowed, String rejectionStatus, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }

        static ValidationResult reject(String status, String reason) {
            return new ValidationResult(false, status, reason);
        }
    }

    /**
     * Validates balance, risk limits, and profit threshold. Does not log — callers
     * log with their own prefix.
     */
    public ValidationResult validatePreExecution(Exchange exchange, TriangleConfig config,
            String cycle, double minVolume, double profit, double estimatedPnlUsd) {
        if (!hasBalanceForAllLegs(exchange, config, cycle, minVolume))
            return ValidationResult.reject(REJECTED_BALANCE, null);

        var riskResult = risk.check(minVolume);
        if (!riskResult.allowed())
            return ValidationResult.reject(REJECTED_RISK, riskResult.reason());

        var profitResult = risk.checkProfit(
                config.getMinProfitPercent(), config.getMinProfitUsd(), profit, estimatedPnlUsd);
        if (!profitResult.allowed())
            return ValidationResult.reject(REJECTED_PROFIT, profitResult.reason());

        return ValidationResult.ok();
    }

    /**
     * Persists the trade, updates stats, optionally sends an alert, and broadcasts.
     * Returns the saved trade.
     */
    private Trade finalizeExecution(Signal signal, List<LegResult> legResults,
            long latencyMs, double estimatedPnl, boolean filled,
            String logPrefix, boolean sendAlert,
            double orderSize, double expectedPnl) {
        executing = false;
        lastTradeCompletedMs = System.currentTimeMillis();

        var trade = buildTrade(signal, legResults, latencyMs, estimatedPnl, filled, orderSize, expectedPnl);
        tradeRepo.save(trade);
        positions.refreshBalances(signal.exchange());
        log.debug("[{}] Positions refreshed — exchange={}", logPrefix, signal.exchange());

        if (filled) {
            executed++;
            triangleConfigRepo.incrementStats(signal.config().getId(), estimatedPnl);
            if (sendAlert)
                alerts.tradeFilled(signal, estimatedPnl);
            log.info("[{}] Trade filled — tradeId={} pnl={} latencyMs={}",
                    logPrefix, trade.getId(), String.format("%.2f", estimatedPnl), latencyMs);
        } else {
            missed++;
            log.warn("[{}] Trade not fully filled — tradeId={} status={}",
                    logPrefix, trade.getId(), trade.getStatus());
        }

        return trade;
    }

    public boolean hasBalanceForAllLegs(Exchange exchange, TriangleConfig config,
            String cycle, double orderSize) {
        var pairs = new String[] { config.getPair1(), config.getPair2(), config.getPair3() };
        var dirs = Cycle.valueOf(cycle).dirs;

        var snapshots = arbitrageEngine.currentSnapshots();

        for (int i = 0; i < 3; i++) {
            var pair = pairs[i];
            var isBuy = BUY.equals(dirs[i]);
            var ccy = isBuy ? pair.substring(3) : pair.substring(0, 3);

            var price = snapshots.stream()
                    .filter(p -> exchange.name().equals(p.exchange()) && pair.equals(p.pair()))
                    .findFirst()
                    .map(snap -> isBuy ? snap.ask() : snap.bid())
                    .orElse(0.0);

            if (price == 0.0) {
                log.warn("[ARB] Balance check — no snapshot for pair={} exchange={}", pair, exchange);
                return false;
            }

            var required = isBuy ? orderSize : orderSize / price;

            if (!positions.hasAvailableBalance(exchange, ccy, required)) {
                log.debug("[ARB] Balance check — {} {} required={} available={}",
                        exchange, ccy, String.format("%.2f", required),
                        String.format("%.2f", positions.getAvailableAmount(exchange, ccy)));
                return false;
            }
        }
        return true;
    }

    private Trade buildTrade(Signal signal, List<LegResult> legResults,
            long latencyMs, double estimatedPnl, boolean filled,
            double orderSize, double expectedPnl) {
        var trade = new Trade()
                .setTime(LocalDateTime.now())
                .setDirection(signal.cycle().name())
                .setSpread(signal.profit())
                .setPnl(estimatedPnl)
                .setStatus(broker.isSimulation() ? SIMULATION : filled ? FILLED : CANCELLED)
                .setLatencyMs(latencyMs)
                .setOrderSize(orderSize)
                .setExpectedPnl(expectedPnl);

        legResults.forEach(lr -> {
            log.info("[TRADE] Leg {} — {} {} price={} volume={} status={} orderId={}",
                    lr.legIndex(), lr.direction(), lr.pair(),
                    lr.price(), String.format("%.6f", lr.volume()),
                    broker.isSimulation() ? SIMULATED : lr.filled() ? FILLED : FAILED,
                    lr.orderId() != null ? lr.orderId() : "-");
            trade.addLeg(new TradeLeg()
                    .setLegIndex(lr.legIndex())
                    .setPair(lr.pair())
                    .setDirection(lr.direction())
                    .setPrice(lr.price())
                    .setVolume(lr.volume())
                    .setStatus(broker.isSimulation() ? SIMULATED : lr.filled() ? FILLED : FAILED)
                    .setOrderId(lr.orderId()));
        });
        log.info("[TRADE] Built — cycle={} spread={} pnl={} status={} latencyMs={}",
                signal.cycle(), String.format("%.5f", signal.profit()),
                String.format("%.2f", estimatedPnl), trade.getStatus(), latencyMs);
        return trade;
    }

    public List<OrderLeg> computeLegs(Signal s, double orderSize) {
        var pairs = new String[]{ s.config().getPair1(), s.config().getPair2(), s.config().getPair3() };
        var dirs  = s.cycle().dirs;
        // quantity in base currency = orderSize / rate(base) — same USD notional on every leg
        var prices = switch (s.cycle()) {
            case BBS -> new double[]{ s.b1().ask(), s.b2().ask(), s.b3().bid() };
            case BSS -> new double[]{ s.b1().ask(), s.b2().bid(), s.b3().bid() };
            case BSB -> new double[]{ s.b1().ask(), s.b2().bid(), s.b3().ask() };
            case SBS -> new double[]{ s.b1().bid(), s.b2().ask(), s.b3().bid() };
        };
        return IntStream.range(0, 3)
                .mapToObj(i -> new OrderLeg(
                        i + 1, pairs[i], dirs[i], prices[i],
                        orderSize / baseRate(pairs[i])))
                .toList();
    }

    private double baseRate(String pair) {
        return currencyRateFeed.getRate(pair.substring(0, 3));
    }

    private double quoteRate(String pair) {
        return currencyRateFeed.getRate(pair.substring(3));
    }

    private double min3(double a, double b, double c) {
        return DoubleStream.of(a, b, c).min().orElse(0);
    }

    public double computePnlFromLegs(List<OrderLeg> legs, double initialAmount) {
        var net = new HashMap<String, Double>();
        for (var leg : legs) {
            var base  = leg.pair().substring(0, 3);
            var quote = leg.pair().substring(3);
            // quantity is in the base currency for all legs
            if (BUY.equals(leg.direction())) {
                net.merge(base,   leg.quantity(),               Double::sum); // receive base
                net.merge(quote, -leg.quantity() * leg.price(), Double::sum); // spend quote
            } else {
                net.merge(base,  -leg.quantity(),               Double::sum); // spend base
                net.merge(quote,  leg.quantity() * leg.price(), Double::sum); // receive quote
            }
        }
        return net.entrySet().stream()
                .mapToDouble(e -> e.getValue() * currencyRateFeed.getRate(e.getKey()))
                .sum();
    }


    // -------------------------------------------------------------------------
    // Stats & broadcast
    // -------------------------------------------------------------------------

    /**
     * Cumulative performance counters since the last application restart.
     *
     * @param detected number of signals that exceeded the edge threshold
     * @param executed number of trades where all legs were filled
     * @param missed   number of cycles aborted due to balance, risk, or order
     *                 failure
     * @param avgEdge  mean profit edge across all detected signals
     */
    public record ArbitrageStats(long detected, long executed, long missed, double avgEdge) {
    }

    public ArbitrageStats getStats() {
        return new ArbitrageStats(detected, executed, missed, detected > 0 ? totalEdge / detected : 0.0);
    }

    public boolean isExecuting() {
        return executing;
    }
}
