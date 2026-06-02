package com.ib.arb.engine;

import com.ib.arb.alert.AlertService;
import com.ib.arb.broker.LegResult;
import com.ib.arb.broker.OrderClient;
import com.ib.arb.broker.OrderLeg;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.CurrencyRateFeed;
import com.ib.arb.marketdata.OrderBook;
import com.ib.arb.model.MissedOpportunity;
import com.ib.arb.model.Trade;
import com.ib.arb.model.TradeLeg;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.position.PositionService;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

@Service
public class AutoTrader {

    private static final Logger log = LoggerFactory.getLogger(AutoTrader.class);

    private final ArbitrageEngine arbitrageEngine;
    private final PositionService positions;
    private final RiskService risk;
    private final Map<Exchange, OrderClient> orderClients;
    private final TradeRepository tradeRepo;
    private final AlertService alerts;
    private final TriangleConfigRepository triangleConfigRepo;
    private final CurrencyRateFeed currencyRateFeed;
    private final MissedOpportunityRepository missedOpportunityRepo;

    @Value("${arb.max-open-orders}")
    private int maxOpenOrders;

    @Value("${arb.trade-cooldown-ms:10000}")
    private long tradeCooldownMs;

    // Per-exchange isolated state
    private final Map<Exchange, AtomicLong>   lastTradeCompletedMap = new ConcurrentHashMap<>();
    private final Map<Exchange, AtomicBoolean> executingMap         = new ConcurrentHashMap<>();
    private final Map<Exchange, AtomicLong>   detectedMap          = new ConcurrentHashMap<>();
    private final Map<Exchange, AtomicLong>   executedMap          = new ConcurrentHashMap<>();
    private final Map<Exchange, AtomicLong>   missedMap            = new ConcurrentHashMap<>();
    private final Map<Exchange, AtomicLong>   totalEdgeBitsMap     = new ConcurrentHashMap<>();

    public AutoTrader(ArbitrageEngine arbitrageEngine, PositionService positions,
                      RiskService risk, List<OrderClient> clients,
                      TradeRepository tradeRepo, AlertService alerts,
                      TriangleConfigRepository triangleConfigRepo, CurrencyRateFeed currencyRateFeed,
                      MissedOpportunityRepository missedOpportunityRepo) {
        this.arbitrageEngine  = arbitrageEngine;
        this.positions        = positions;
        this.risk             = risk;
        this.orderClients     = clients.stream().collect(
            Collectors.toMap(OrderClient::getExchange, Function.identity()));
        this.tradeRepo        = tradeRepo;
        this.alerts           = alerts;
        this.triangleConfigRepo = triangleConfigRepo;
        this.currencyRateFeed = currencyRateFeed;
        this.missedOpportunityRepo = missedOpportunityRepo;
    }

    // ── Automated path ────────────────────────────────────────────────────────

    public void attemptArbitrage(Exchange exchange) {
        var broker = orderClients.get(exchange);
        if (broker == null) {
            log.warn("[ARB] No OrderClient registered for {}", exchange);
            return;
        }
        if (broker.openOrderCount() >= maxOpenOrders) {
            log.debug("[ARB] {} — skipping, open order limit reached", exchange);
            return;
        }
        var lastTrade = lastTradeTime(exchange);
        if (System.currentTimeMillis() - lastTrade.get() < tradeCooldownMs) {
            log.debug("[ARB] {} — skipping, within cooldown", exchange);
            return;
        }

        var signal = arbitrageEngine.scanForOpportunities(exchange);
        if (signal.isEmpty()) {
            log.debug("[ARB] {} — no opportunity this cycle", exchange);
            return;
        }
        executeArbitrage(signal.get(), broker);
    }

    private void executeArbitrage(Signal s, OrderClient broker) {
        counter(detectedMap, s.exchange()).incrementAndGet();
        totalEdgeBits(s.exchange()).addAndGet(Double.doubleToLongBits(s.profit()));

        var maxVolume  = calculateMaxVolume(s);
        maxVolume = Math.min(effectiveOrderSize(s.exchange()), maxVolume);
        var legs = computeLegs(s, maxVolume);
        var expectedPnl = computePnlFromLegs(legs, maxVolume);

        var v = validatePreExecution(s.exchange(), s.config(), s.cycle().name(), maxVolume, s.profit(), expectedPnl);
        if (!v.allowed()) {
            recordMissed(s, legs, maxVolume, expectedPnl, v);
            counter(missedMap, s.exchange()).incrementAndGet();
            return;
        }

        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = legs.stream()
                .map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(), l.price(), l.quantity(), true, null))
                .toList();
            log.info("[SIM] {} Cycle {} | {} | profit={}", s.exchange(), s.cycle(),
                legResults.stream().map(l -> l.direction() + " " + l.pair())
                    .reduce((a, b) -> a + ", " + b).orElse(""),
                String.format("%.5f", s.profit()));
        } else {
            legResults = broker.placeOrderLegs(legs);
        }
        var latencyMs = System.currentTimeMillis() - start;
        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        finalizeExecution(s, broker, legResults, latencyMs, filled ? expectedPnl : 0, filled, "ARB", true, maxVolume, expectedPnl);
    }

    // ── Manual path ───────────────────────────────────────────────────────────

    public ManualTradeResult executeTrade(TriangleConfig config, String cycle, List<OrderLeg> legs) {
        var exchange = Exchange.valueOf(config.getExchange().toUpperCase());
        var broker = orderClients.get(exchange);
        if (broker == null)
            return new ManualTradeResult(-1, "REJECTED_NO_CLIENT", 0.0);

        if (broker.openOrderCount() >= maxOpenOrders)
            return new ManualTradeResult(-1, "REJECTED_OPEN_ORDERS", 0.0);

        var cycleEnum = Cycle.valueOf(cycle);
        var notional = legs.get(0).price() * legs.get(0).quantity();
        var edge = switch (cycleEnum) {
            case BBS -> legs.get(0).price() * legs.get(1).price() - legs.get(2).price();
            case BSS -> legs.get(0).price() - legs.get(1).price() * legs.get(2).price();
            case BSB -> legs.get(0).price() * legs.get(2).price() - legs.get(1).price();
            case SBS -> legs.get(1).price() - legs.get(0).price() * legs.get(2).price();
        };

        var manualExpectedPnl = computePnlFromLegs(legs, notional);
        var v = validatePreExecution(exchange, config, cycle, notional, edge, manualExpectedPnl);
        if (!v.allowed()) return new ManualTradeResult(-1, v.rejectionStatus(), 0.0);

        var start = System.currentTimeMillis();
        List<LegResult> legResults = broker.isSimulation()
            ? legs.stream().map(l -> new LegResult(l.legIndex(), l.pair(), l.direction(), l.price(), l.quantity(), true, null)).toList()
            : broker.placeOrderLegs(legs);
        var latencyMs = System.currentTimeMillis() - start;

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var b1 = new OrderBook(config.getPair1(), legs.get(0).price(), legs.get(0).quantity(), legs.get(0).price(), legs.get(0).quantity());
        var b2 = new OrderBook(config.getPair2(), legs.get(1).price(), legs.get(1).quantity(), legs.get(1).price(), legs.get(1).quantity());
        var b3 = new OrderBook(config.getPair3(), legs.get(2).price(), legs.get(2).quantity(), legs.get(2).price(), legs.get(2).quantity());
        var signal = new Signal(exchange, config, cycleEnum, edge, b1, b2, b3);
        var trade  = finalizeExecution(signal, broker, legResults, latencyMs, filled ? edge * notional : 0, filled, "MANUAL", false, notional, manualExpectedPnl);
        return new ManualTradeResult(trade.getId(), trade.getStatus(), filled ? edge * notional : 0);
    }

    public record ManualTradeResult(long tradeId, String status, double pnl) {}

    // ── Shared helpers ────────────────────────────────────────────────────────

    private record ValidationResult(boolean allowed, String rejectionStatus, String reason) {
        static ValidationResult ok() { return new ValidationResult(true, null, null); }
        static ValidationResult reject(String s, String r) { return new ValidationResult(false, s, r); }
    }

    public ValidationResult validatePreExecution(Exchange exchange, TriangleConfig config,
            String cycle, double minVolume, double profit, double estimatedPnlUsd) {
        if (!hasBalanceForAllLegs(exchange, config, cycle, minVolume))
            return ValidationResult.reject(REJECTED_BALANCE, null);
        var riskResult = risk.check(exchange, minVolume);
        if (!riskResult.allowed())
            return ValidationResult.reject(REJECTED_RISK, riskResult.reason());
        var profitResult = risk.checkProfit(config.getMinProfitPercent(), config.getMinProfitUsd(), profit, estimatedPnlUsd);
        if (!profitResult.allowed())
            return ValidationResult.reject(REJECTED_PROFIT, profitResult.reason());
        return ValidationResult.ok();
    }

    private Trade finalizeExecution(Signal signal, OrderClient broker, List<LegResult> legResults,
            long latencyMs, double estimatedPnl, boolean filled,
            String logPrefix, boolean sendAlert, double orderSize, double expectedPnl) {
        lastTradeTime(signal.exchange()).set(System.currentTimeMillis());
        executing(signal.exchange()).set(false);

        var trade = buildTrade(signal, broker, legResults, latencyMs, estimatedPnl, filled, orderSize, expectedPnl);
        tradeRepo.save(trade);
        positions.refreshBalances(signal.exchange());

        if (filled) {
            counter(executedMap, signal.exchange()).incrementAndGet();
            triangleConfigRepo.incrementStats(signal.config().getId(), estimatedPnl);
            if (sendAlert) alerts.tradeFilled(signal, estimatedPnl);
            log.info("[{}] {} trade filled — tradeId={} pnl={} latencyMs={}",
                logPrefix, signal.exchange(), trade.getId(), String.format("%.2f", estimatedPnl), latencyMs);
        } else {
            counter(missedMap, signal.exchange()).incrementAndGet();
            log.warn("[{}] {} trade not fully filled — tradeId={}", logPrefix, signal.exchange(), trade.getId());
        }
        return trade;
    }

    public boolean hasBalanceForAllLegs(Exchange exchange, TriangleConfig config,
            String cycle, double orderSize) {
        var pairs = new String[]{ config.getPair1(), config.getPair2(), config.getPair3() };
        var dirs  = Cycle.valueOf(cycle).dirs;
        var snapshots = arbitrageEngine.currentSnapshots();
        for (int i = 0; i < 3; i++) {
            var pair  = pairs[i];
            var isBuy = BUY.equals(dirs[i]);
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

    public double calculateMaxVolume(Signal s) {
        var pairs = new String[]{ s.config().getPair1(), s.config().getPair2(), s.config().getPair3() };
        return switch (s.cycle()) {
            case BBS -> min3(s.b1().askQty() * s.b1().ask() * quoteRate(pairs[0]),
                             s.b2().askQty() * s.b2().ask() * quoteRate(pairs[1]),
                             s.b3().bidQty() * s.b3().bid() * quoteRate(pairs[2]));
            case BSS -> min3(s.b1().askQty() * s.b1().ask() * quoteRate(pairs[0]),
                             s.b2().bidQty() * s.b2().bid() * quoteRate(pairs[1]),
                             s.b3().bidQty() * s.b3().bid() * quoteRate(pairs[2]));
            case BSB -> min3(s.b1().askQty() * s.b1().ask() * quoteRate(pairs[0]),
                             s.b2().bidQty() * s.b2().bid() * quoteRate(pairs[1]),
                             s.b3().askQty() * s.b3().ask() * quoteRate(pairs[2]));
            case SBS -> min3(s.b1().bidQty() * s.b1().bid() * quoteRate(pairs[0]),
                             s.b2().askQty() * s.b2().ask() * quoteRate(pairs[1]),
                             s.b3().bidQty() * s.b3().bid() * quoteRate(pairs[2]));
        };
    }

    public List<OrderLeg> computeLegs(Signal s, double orderSize) {
        var pairs  = new String[]{ s.config().getPair1(), s.config().getPair2(), s.config().getPair3() };
        var dirs   = s.cycle().dirs;
        double[] prices = switch (s.cycle()) {
            case BBS -> new double[]{ s.b1().ask(), s.b2().ask(), s.b3().bid() };
            case BSS -> new double[]{ s.b1().ask(), s.b2().bid(), s.b3().bid() };
            case BSB -> new double[]{ s.b1().ask(), s.b2().bid(), s.b3().ask() };
            case SBS -> new double[]{ s.b1().bid(), s.b2().ask(), s.b3().bid() };
        };
        return IntStream.range(0, 3)
            .mapToObj(i -> new OrderLeg(i + 1, pairs[i], dirs[i], prices[i], orderSize / baseRate(pairs[i])))
            .toList();
    }

    public double computePnlFromLegs(List<OrderLeg> legs, double initialAmount) {
        var net = new HashMap<String, Double>();
        for (var leg : legs) {
            var base  = leg.pair().substring(0, 3);
            var quote = leg.pair().substring(3);
            if (BUY.equals(leg.direction())) {
                net.merge(base,   leg.quantity(),               Double::sum);
                net.merge(quote, -leg.quantity() * leg.price(), Double::sum);
            } else {
                net.merge(base,  -leg.quantity(),               Double::sum);
                net.merge(quote,  leg.quantity() * leg.price(), Double::sum);
            }
        }
        return net.entrySet().stream()
            .mapToDouble(e -> e.getValue() * currencyRateFeed.getRate(e.getKey()))
            .sum();
    }

    private Trade buildTrade(Signal signal, OrderClient broker, List<LegResult> legResults,
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
            .setExpectedPnl(expectedPnl)
            .setExchange(signal.exchange().name());

        legResults.forEach(lr -> trade.addLeg(new TradeLeg()
            .setLegIndex(lr.legIndex())
            .setPair(lr.pair())
            .setDirection(lr.direction())
            .setPrice(lr.price())
            .setVolume(lr.volume())
            .setStatus(broker.isSimulation() ? SIMULATED : lr.filled() ? FILLED : FAILED)
            .setOrderId(lr.orderId())));
        return trade;
    }

    private void recordMissed(Signal s, List<OrderLeg> legs, double maxVolume,
            double expectedPnl, ValidationResult v) {
        switch (v.rejectionStatus()) {
            case REJECTED_BALANCE -> log.warn("[ARB] {} Missed — insufficient balance for triangle={}", s.exchange(), s.config().getId());
            case REJECTED_RISK    -> log.warn("[ARB] {} Missed — risk check failed: {}", s.exchange(), v.reason());
            case REJECTED_PROFIT  -> log.warn("[ARB] {} Missed — profit threshold not met: {}", s.exchange(), v.reason());
        }
        missedOpportunityRepo.save(new MissedOpportunity()
            .setTime(LocalDateTime.now())
            .setTriangleId(s.config().getId())
            .setExchange(s.exchange().name())
            .setPair1(s.config().getPair1()).setPair2(s.config().getPair2()).setPair3(s.config().getPair3())
            .setCycle(s.cycle().name()).setEdge(s.profit()).setOrderSize(maxVolume)
            .setRejection(v.rejectionStatus()).setReason(v.reason()).setExpectedPnl(expectedPnl)
            .setLeg1Price(legs.get(0).price()).setLeg1Volume(legs.get(0).quantity())
            .setLeg2Price(legs.get(1).price()).setLeg2Volume(legs.get(1).quantity())
            .setLeg3Price(legs.get(2).price()).setLeg3Volume(legs.get(2).quantity()));
    }

    // ── Stats & broadcast ─────────────────────────────────────────────────────

    public record ArbitrageStats(long detected, long executed, long missed, double avgEdge) {}

    public ArbitrageStats getStats() {
        long det  = detectedMap.values().stream().mapToLong(AtomicLong::get).sum();
        long exe  = executedMap.values().stream().mapToLong(AtomicLong::get).sum();
        long mis  = missedMap.values().stream().mapToLong(AtomicLong::get).sum();
        double te = totalEdgeBitsMap.values().stream()
            .mapToDouble(l -> Double.longBitsToDouble(l.get())).sum();
        return new ArbitrageStats(det, exe, mis, det > 0 ? te / det : 0.0);
    }

    public boolean isExecuting() {
        return executingMap.values().stream().anyMatch(AtomicBoolean::get);
    }

    // ── Per-exchange state accessors ─────────────────────────────────────────

    private AtomicLong lastTradeTime(Exchange e) {
        return lastTradeCompletedMap.computeIfAbsent(e, x -> new AtomicLong(0));
    }
    private AtomicBoolean executing(Exchange e) {
        return executingMap.computeIfAbsent(e, x -> new AtomicBoolean(false));
    }
    private AtomicLong counter(Map<Exchange, AtomicLong> map, Exchange e) {
        return map.computeIfAbsent(e, x -> new AtomicLong(0));
    }
    private AtomicLong totalEdgeBits(Exchange e) {
        return totalEdgeBitsMap.computeIfAbsent(e, x -> new AtomicLong(0));
    }
    private double effectiveOrderSize(Exchange e) {
        return orderClients.containsKey(e)
            ? 100_000 // default; overridden by ExchangeConfig via RiskService gate
            : 100_000;
    }
    private double baseRate(String pair)  { return currencyRateFeed.getRate(pair.substring(0, 3)); }
    private double quoteRate(String pair) { return currencyRateFeed.getRate(pair.substring(3)); }
    private double min3(double a, double b, double c) { return DoubleStream.of(a, b, c).min().orElse(0); }
}
