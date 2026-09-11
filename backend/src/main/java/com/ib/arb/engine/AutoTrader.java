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
import com.ib.arb.repository.ExchangeConfigRepository;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ExchangeConfigRepository configRepo;

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

    /** Per-exchange critical alerts (e.g. precision-error rejections) — set when live order
     *  placement is halted, surfaced to the Dashboard, cleared on restart. */
    private final Map<Exchange, String> exchangeAlerts = new ConcurrentHashMap<>();

    public AutoTrader(ArbitrageEngine arbitrageEngine, PositionService positions,
                      RiskService risk, List<OrderClient> clients,
                      TradeRepository tradeRepo, AlertService alerts,
                      TriangleConfigRepository triangleConfigRepo, CurrencyRateFeed currencyRateFeed,
                      MissedOpportunityRepository missedOpportunityRepo,
                      ExchangeConfigRepository configRepo) {
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
        this.configRepo       = configRepo;
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
        var quoteRates = captureQuoteRates(legs);

        // Phase 1: validate from cache — avoid API calls for rejected opportunities
        var v = validatePreExecution(s.exchange(), s.config(), s.cycle().name(), maxVolume, s.profit(), expectedPnl);
        if (!v.allowed()) {
            recordMissed(s, legs, maxVolume, expectedPnl, v);
            counter(missedMap, s.exchange()).incrementAndGet();
            return;
        }

        // Phase 2: real trades only — refresh then re-check balance with fresh data
        if (!broker.isSimulation()) {
            positions.refreshBalances(s.exchange());
            if (!hasBalanceForAllLegs(s.exchange(), s.config(), s.cycle().name(), maxVolume)) {
                var balReject = ValidationResult.reject(REJECTED_BALANCE, "Insufficient balance after pre-trade refresh");
                recordMissed(s, legs, maxVolume, expectedPnl, balReject);
                counter(missedMap, s.exchange()).incrementAndGet();
                return;
            }
        }

        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = simulatedLegResults(legs, broker);
            log.info("[SIM] {} Cycle {} | {} | profit={}", s.exchange(), s.cycle(),
                legResults.stream().map(l -> l.direction() + " " + l.pair())
                    .reduce((a, b) -> a + ", " + b).orElse(""),
                String.format("%.5f", s.profit()));
        } else {
            legResults = broker.placeOrderLegs(legs);
            reportIfHaltingError(s.exchange(), broker);
        }
        var latencyMs = System.currentTimeMillis() - start;
        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        finalizeExecution(s, broker, legResults, latencyMs, filled ? expectedPnl : 0, filled, "ARB", true, maxVolume, expectedPnl, quoteRates);
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
        var quoteRates = captureQuoteRates(legs);
        var v = validatePreExecution(exchange, config, cycle, notional, edge, manualExpectedPnl);
        if (!v.allowed()) return new ManualTradeResult(-1, v.rejectionStatus(), 0.0);

        var start = System.currentTimeMillis();
        List<LegResult> legResults;
        if (broker.isSimulation()) {
            legResults = simulatedLegResults(legs, broker);
        } else {
            legResults = broker.placeOrderLegs(legs);
            reportIfHaltingError(exchange, broker);
        }
        var latencyMs = System.currentTimeMillis() - start;

        var filled = !legResults.isEmpty() && legResults.stream().allMatch(LegResult::filled);
        var b1 = new OrderBook(config.getPair1(), legs.get(0).price(), legs.get(0).quantity(), legs.get(0).price(), legs.get(0).quantity());
        var b2 = new OrderBook(config.getPair2(), legs.get(1).price(), legs.get(1).quantity(), legs.get(1).price(), legs.get(1).quantity());
        var b3 = new OrderBook(config.getPair3(), legs.get(2).price(), legs.get(2).quantity(), legs.get(2).price(), legs.get(2).quantity());
        var signal = new Signal(exchange, config, cycleEnum, edge, b1, b2, b3);
        var trade  = finalizeExecution(signal, broker, legResults, latencyMs, filled ? edge * notional : 0, filled, "MANUAL", false, notional, manualExpectedPnl, quoteRates);
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
            String logPrefix, boolean sendAlert, double orderSize, double expectedPnl,
            List<Double> quoteRates) {
        lastTradeTime(signal.exchange()).set(System.currentTimeMillis());
        executing(signal.exchange()).set(false);

        var trade = buildTrade(signal, broker, legResults, latencyMs, estimatedPnl, filled, orderSize, expectedPnl, quoteRates);
        tradeRepo.save(trade);
        // Simulation trades need no position refresh; real trades wait 2s for order settlement
        if (!broker.isSimulation()) positions.refreshBalancesDelayed(signal.exchange(), 2000);

        if (filled) {
            counter(executedMap, signal.exchange()).incrementAndGet();
            triangleConfigRepo.incrementStats(signal.config().getId(), estimatedPnl);
            arbitrageEngine.invalidateSnapshots(signal.exchange(),
                signal.config().getPair1(), signal.config().getPair2(), signal.config().getPair3());
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
            var parts = splitPair(pair);
            var ccy   = isBuy ? parts[1] : parts[0];
            var norm  = pair.replace("/", "");
            var price = snapshots.stream()
                .filter(p -> exchange.name().equals(p.exchange()) && norm.equalsIgnoreCase(p.pair().replace("/", "")))
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
            .mapToObj(i -> new OrderLeg(i + 1, pairs[i], dirs[i], prices[i], orderSize / baseRate(pairs[i]), "LIMIT"))
            .toList();
    }

    public double computePnlFromLegs(List<OrderLeg> legs, double initialAmount) {
        var net = new HashMap<String, Double>();
        for (var leg : legs) {
            var parts = splitPair(leg.pair());
            var base  = parts[0];
            var quote = parts[1];
            if (BUY.equals(leg.direction())) {
                net.merge(base,   leg.quantity(),               Double::sum);
                net.merge(quote, -leg.quantity() * leg.price(), Double::sum);
            } else {
                net.merge(base,  -leg.quantity(),               Double::sum);
                net.merge(quote,  leg.quantity() * leg.price(), Double::sum);
            }
        }
        return net.entrySet().stream()
            .mapToDouble(e -> e.getValue() * resolveUsdRate(e.getKey()))
            .sum();
    }

    private Trade buildTrade(Signal signal, OrderClient broker, List<LegResult> legResults,
            long latencyMs, double estimatedPnl, boolean filled,
            double orderSize, double expectedPnl, List<Double> quoteRates) {
        var trade = new Trade()
            .setTime(LocalDateTime.now(ZoneOffset.UTC))
            .setDirection(signal.cycle().name())
            .setSpread(signal.profit())
            .setPnl(estimatedPnl)
            .setStatus(broker.isSimulation() ? SIMULATION : filled ? FILLED : CANCELLED)
            .setLatencyMs(latencyMs)
            .setOrderSize(orderSize)
            .setExpectedPnl(expectedPnl)
            .setExchange(signal.exchange().name())
            .setProfitPercent(orderSize > 0 ? estimatedPnl / orderSize * 100.0 : 0.0)
            .setTriangleDisplayOrder(signal.config().getDisplayOrder())
            .setPair1(signal.config().getPair1())
            .setPair2(signal.config().getPair2())
            .setPair3(signal.config().getPair3());

        for (int i = 0; i < legResults.size(); i++) {
            var lr = legResults.get(i);
            var rate = (quoteRates != null && i < quoteRates.size()) ? quoteRates.get(i) : null;
            trade.addLeg(new TradeLeg()
                .setLegIndex(lr.legIndex())
                .setPair(lr.pair())
                .setDirection(lr.direction())
                .setPrice(lr.price())
                .setVolume(lr.volume())
                .setStatus(broker.isSimulation() ? SIMULATED : lr.filled() ? FILLED : FAILED)
                .setOrderId(lr.orderId())
                .setQuoteRate(rate));
        }
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
            .setTime(LocalDateTime.now(ZoneOffset.UTC))
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

    // ── Per-exchange alerts (precision-error halts) ──────────────────────────

    /** If the broker reported a critical error (e.g. precision rejection), record it as an
     *  exchange-level alert so {@code ExchangeManager} halts scanning and the Dashboard shows it. */
    private void reportIfHaltingError(Exchange exchange, OrderClient broker) {
        broker.consumeError().ifPresent(err -> {
            exchangeAlerts.put(exchange, err);
            log.error("[ARB] {} — halting trading: {}", exchange, err);
        });
    }

    public Optional<String> getExchangeAlert(Exchange exchange) {
        return Optional.ofNullable(exchangeAlerts.get(exchange));
    }

    public void clearExchangeAlert(Exchange exchange) {
        exchangeAlerts.remove(exchange);
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
        return configRepo.findByExchange(e.name())
            .map(cfg -> {
                double size = Math.min(cfg.getOrderSizeUsd(), cfg.getPositionLimitUsd());
                if (cfg.getOrderSizeUsd() > cfg.getPositionLimitUsd())
                    log.debug("[ARB] {} order size capped at position limit ({} → {})",
                        e, cfg.getOrderSizeUsd(), size);
                return size;
            })
            .orElse(100_000.0);
    }
    private double baseRate(String pair)  { return resolveUsdRate(splitPair(pair)[0]); }
    private double quoteRate(String pair) { return resolveUsdRate(splitPair(pair)[1]); }

    // Longest-first so "USDT" matches before "USD", "USDC" before "USD", etc.
    private static final List<String> QUOTE_SUFFIXES = List.of(
        "USDT", "USDC", "BUSD", "EUR", "GBP", "JPY", "TRY", "USD", "BTC", "ETH"
    );

    private static String[] splitPair(String pair) {
        var norm = pair.replace("/", "").toUpperCase();
        for (var q : QUOTE_SUFFIXES) {
            if (norm.endsWith(q) && norm.length() > q.length())
                return new String[]{ norm.substring(0, norm.length() - q.length()), q };
        }
        return new String[]{ norm.substring(0, 3), norm.substring(3) };
    }
    private double min3(double a, double b, double c) { return DoubleStream.of(a, b, c).min().orElse(0); }

    /**
     * Builds simulated {@link LegResult}s using each pair's real exchange precision (via
     * {@link OrderClient#getPrecision}), so simulated trades record the same rounded
     * price/quantity a real order would have sent — not the raw unrounded computed values.
     * Note: for Bitfinex specifically this is an approximation, since its real precision rule
     * is significant-figure based rather than a fixed per-pair decimal count.
     */
    private List<LegResult> simulatedLegResults(List<OrderLeg> legs, OrderClient broker) {
        return legs.stream()
            .map(l -> {
                var prec = broker.getPrecision(l.pair());
                var price = round(l.price(), prec[0]);
                var qty   = round(l.quantity(), prec[1]);
                return new LegResult(l.legIndex(), l.pair(), l.direction(), price, qty, true, null, null);
            })
            .toList();
    }

    private static double round(double v, int decimals) {
        return java.math.BigDecimal.valueOf(v).setScale(decimals, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private List<Double> captureQuoteRates(List<OrderLeg> legs) {
        var rates = new ArrayList<Double>(legs.size());
        for (var leg : legs) {
            var parts    = splitPair(leg.pair());
            var baseUsd  = resolveUsdRate(parts[0]);
            var quoteUsd = resolveUsdRate(parts[1]);
            rates.add(baseUsd > 0 && quoteUsd > 0 ? baseUsd / quoteUsd : null);
        }
        return rates;
    }

    private double resolveUsdRate(String currency) {
        // Step 1: fiat → CurrencyLayer; crypto → Crypto Aggregator feed
        var rate = CurrencyRateFeed.isFiat(currency)
            ? currencyRateFeed.getRate(currency)
            : currencyRateFeed.getCryptoRate(currency);
        if (rate > 0) return rate;

        // Step 2: live orderbook mid-price
        var snapshots = arbitrageEngine.currentSnapshots();
        for (var suffix : new String[]{"USDT", "USD", "USDC", "BUSD"}) {
            var found = snapshots.stream()
                .filter(s -> s.pair().replace("/", "").equalsIgnoreCase(currency + suffix))
                .findFirst()
                .map(s -> (s.bid() + s.ask()) / 2.0)
                .orElse(0.0);
            if (found > 0) return found;
        }

        // Step 3: BTC cross-rate fallback (e.g. ETH → ETH/BTC mid × BTC/USD)
        var btcRate = currencyRateFeed.getCryptoRate("BTC");
        if (btcRate > 0) {
            var viaBtc = snapshots.stream()
                .filter(s -> s.pair().replace("/", "").equalsIgnoreCase(currency + "BTC"))
                .findFirst()
                .map(s -> (s.bid() + s.ask()) / 2.0 * btcRate)
                .orElse(0.0);
            if (viaBtc > 0) return viaBtc;
        }
        return 0.0;
    }
}
