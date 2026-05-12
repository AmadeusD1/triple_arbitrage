package com.ib.arb.execution;

import com.ib.arb.alert.AlertService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.broker.KrakenOrderClient.LegResult;
import com.ib.arb.broker.OrderLeg;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.CurrencyRateFeed;
import com.ib.arb.marketdata.OrderBook;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.Trade;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.position.PositionService;
import com.ib.arb.repository.MissedOpportunityRepository;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.risk.RiskService;
import com.ib.arb.engine.AutoTrader;
import com.ib.arb.engine.ArbitrageEngine;
import com.ib.arb.scanner.Cycle;
import com.ib.arb.scanner.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoTraderTest {

    ArbitrageEngine arbitrageEngine                      = mock(ArbitrageEngine.class);
    PositionService positions                            = mock(PositionService.class);
    RiskService risk                                     = mock(RiskService.class);
    KrakenOrderClient broker                             = mock(KrakenOrderClient.class);
    TradeRepository tradeRepo                            = mock(TradeRepository.class);
    AlertService alerts                                  = mock(AlertService.class);
    TriangleConfigRepository triangleRepo                = mock(TriangleConfigRepository.class);
    CurrencyRateFeed currencyRateFeed                    = mock(CurrencyRateFeed.class);
    MissedOpportunityRepository missedOpportunityRepo    = mock(MissedOpportunityRepository.class);

    AutoTrader autoTrader;

    static final TriangleConfig TRI;
    static {
        TRI = new TriangleConfig();
        TRI.setPair1("EURUSD");
        TRI.setPair2("USDJPY");
        TRI.setPair3("EURJPY");
        TRI.setExchange("KRAKEN");
    }

    static final OrderBook OB = new OrderBook("EURUSD", 1.08, 1_000_000.0, 1.081, 1_000_000.0);

    static final Signal SIGNAL_A = new Signal(Exchange.KRAKEN, TRI, Cycle.BBS, 0.001, OB, OB, OB);

    // EURJPY sell at 162.25 > ask_EURUSD*ask_USDJPY ≈ 162.021 → profitable execution
    static final List<LegResult> THREE_FILLED_LEGS = List.of(
        new LegResult(1, "EURUSD", "BUY",  1.0801, 92584.0, true,  "TXID-1"),
        new LegResult(2, "USDJPY", "BUY",  150.01, 617.2,   true,  "TXID-2"),
        new LegResult(3, "EURJPY", "SELL", 162.25, 617.2,   true,  "TXID-3")
    );

    static final List<LegResult> ONE_FAILED_LEG = List.of(
        new LegResult(1, "EURUSD", "BUY",  1.0801, 92584.0, true,  "TXID-1"),
        new LegResult(2, "USDJPY", "BUY",  150.01, 666.6,   false, null)
    );

    @BeforeEach
    void setup() {
        autoTrader = new AutoTrader(arbitrageEngine, positions, risk, broker,
                                    tradeRepo, alerts, triangleRepo, currencyRateFeed,
                                    missedOpportunityRepo);
        when(currencyRateFeed.getAllRates()).thenReturn(Map.of());
        when(currencyRateFeed.getRate(anyString())).thenReturn(1.0);
        ReflectionTestUtils.setField(autoTrader, "orderSizeUsd", 100_000.0);
        ReflectionTestUtils.setField(autoTrader, "maxOpenOrders", 1);
        ReflectionTestUtils.setField(autoTrader, "tradeCooldownMs", 0L);

        when(tradeRepo.findTop20ByOrderByTimeDesc()).thenReturn(List.of());
        when(broker.isConnected()).thenReturn(true);
        when(arbitrageEngine.currentSnapshots()).thenReturn(List.of(
            new PriceSnapshot("KRAKEN", "EURUSD", 1.0800, 1.0801),
            new PriceSnapshot("KRAKEN", "USDJPY", 150.00, 150.01),
            new PriceSnapshot("KRAKEN", "EURJPY", 162.00, 162.10)
        ));
        when(positions.getAvailableAmount(any(), anyString())).thenReturn(200_000.0);
        when(risk.checkProfit(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(RiskService.RiskResult.ok());
        when(tradeRepo.save(any())).thenAnswer(inv -> {
            var t = (Trade) inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
    }

    // ── early exits ───────────────────────────────────────────────────────────

    @Test
    void skips_whenTooManyOpenOrders() {
        when(broker.openOrderCount()).thenReturn(1);

        autoTrader.attemptArbitrage();

        verify(arbitrageEngine, never()).scanForOpportunities();
    }

    @Test
    void skips_whenNoSignal() {
        when(broker.openOrderCount()).thenReturn(0);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.empty());

        autoTrader.attemptArbitrage();

        verify(positions, never()).hasAvailableBalance(any(), any(), anyDouble());
    }

    @Test
    void incrementsMissed_whenBalanceInsufficient() {
        when(broker.openOrderCount()).thenReturn(0);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(false);

        autoTrader.attemptArbitrage();

        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
        verify(risk, never()).check(anyDouble());
    }

    @Test
    void incrementsMissed_whenRiskBlocked() {
        when(broker.openOrderCount()).thenReturn(0);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.block("limit"));

        autoTrader.attemptArbitrage();

        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
        verify(broker, never()).placeOrder(any(), anyDouble());
    }

    // ── simulation mode ───────────────────────────────────────────────────────

    @Test
    void simulation_recordsFill_withoutCallingBroker() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(broker.computeLegs(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        verify(broker, never()).placeOrder(any(), anyDouble());
        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SIMULATION");
        assertThat(saved.getLegs()).hasSize(3);
        assertThat(saved.getLegs()).allMatch(l -> "SIMULATED".equals(l.getStatus()));
        assertThat(autoTrader.getStats().executed()).isEqualTo(1);
        verify(triangleRepo).incrementStats(any(), anyDouble());
    }

    // ── live mode ─────────────────────────────────────────────────────────────

    @Test
    void live_recordsFill_with3Legs() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(false);
        when(broker.placeOrderLegs(any())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("FILLED");
        // pnl = computePnlFromResults: 100_000 / 1.0801 / 150.01 * 162.25 - 100_000 ≈ 140
        assertThat(saved.getPnl()).isGreaterThan(100.0);
        assertThat(saved.getLegs()).hasSize(3);
        assertThat(saved.getLegs()).allMatch(l -> "FILLED".equals(l.getStatus()));
        assertThat(saved.getLegs().get(0).getPair()).isEqualTo("EURUSD");
        assertThat(saved.getLegs().get(0).getDirection()).isEqualTo("BUY");
        assertThat(autoTrader.getStats().executed()).isEqualTo(1);
        verify(alerts).tradeFilled(eq(SIGNAL_A), anyDouble());
        verify(triangleRepo).incrementStats(any(), anyDouble());
    }

    @Test
    void live_recordsCancelled_withPartialLegs_andIncrementsMissed() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(false);
        when(broker.placeOrderLegs(any())).thenReturn(ONE_FAILED_LEG);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("CANCELLED");
        assertThat(saved.getPnl()).isEqualTo(0.0);
        assertThat(saved.getLegs()).hasSize(2);
        assertThat(saved.getLegs().get(0).getStatus()).isEqualTo("FILLED");
        assertThat(saved.getLegs().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
    }

    @Test
    void live_recordsCancelled_whenBrokerReturnsEmpty() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(false);
        when(broker.placeOrderLegs(any())).thenReturn(List.of());
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CANCELLED");
        assertThat(captor.getValue().getLegs()).isEmpty();
    }

    @Test
    void incrementStats_notCalled_whenCancelled() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(false);
        when(broker.placeOrderLegs(any())).thenReturn(ONE_FAILED_LEG);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        verify(triangleRepo, never()).incrementStats(any(), anyDouble());
    }

    // ── stats ─────────────────────────────────────────────────────────────────

    @Test
    void getStats_tracksAvgEdge_acrossMultipleCycles() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());
        when(broker.computeLegs(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);

        var sig1 = new Signal(Exchange.KRAKEN, TRI, Cycle.BBS, 0.001, OB, OB, OB);
        var sig2 = new Signal(Exchange.KRAKEN, TRI, Cycle.BBS, 0.003, OB, OB, OB);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(sig1), Optional.of(sig2));

        autoTrader.attemptArbitrage();
        autoTrader.attemptArbitrage();

        var stats = autoTrader.getStats();
        assertThat(stats.detected()).isEqualTo(2);
        assertThat(stats.avgEdge()).isEqualTo(0.002, offset(0.0001));
    }

    @Test
    void getStats_returnsZeroAvgEdge_whenNothingDetected() {
        assertThat(autoTrader.getStats().avgEdge()).isEqualTo(0.0);
    }

    // ── cycle direction ───────────────────────────────────────────────────────

    @Test
    void cycleA_checksQuoteCurrency() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(broker.computeLegs(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);

        autoTrader.attemptArbitrage();

        verify(positions).hasAvailableBalance(Exchange.KRAKEN, "USD", 100_000.0);
    }

    @Test
    void cycleB_checksBaseCurrency() {
        var signalB = new Signal(Exchange.KRAKEN, TRI, Cycle.BSS, 0.001, OB, OB, OB);
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(broker.computeLegs(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(signalB));
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);

        autoTrader.attemptArbitrage();

        // Cycle B leg1 = SELL EURUSD → ccy=EUR, required = orderSizeUsd / bid_EURUSD
        verify(positions).hasAvailableBalance(eq(Exchange.KRAKEN), eq("EUR"), anyDouble());
    }

    // ── executeTrade (manual) — early rejections ──────────────────────────────

    static final List<OrderLeg> MANUAL_LEGS = List.of(
        new OrderLeg(1, "EURUSD", "BUY",  1.0801, 10_000.0),
        new OrderLeg(2, "USDJPY", "BUY",  150.01,    72.0),
        new OrderLeg(3, "EURJPY", "SELL", 162.00,    67.0)
    );
    // notional = leg1.price × leg1.volume = 1.0801 × 10_000 ≈ 10_801 USD

    @Test
    void manualTrade_rejected_whenTooManyOpenOrders() {
        when(broker.openOrderCount()).thenReturn(1);

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("REJECTED_OPEN_ORDERS");
        verify(positions, never()).hasAvailableBalance(any(), any(), anyDouble());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    void manualTrade_rejected_whenBalanceInsufficient() {
        when(broker.openOrderCount()).thenReturn(0);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(false);

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("REJECTED_BALANCE");
        verify(risk, never()).check(anyDouble());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    void manualTrade_rejected_whenRiskBlocked() {
        when(broker.openOrderCount()).thenReturn(0);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.block("limit"));

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("REJECTED_RISK");
        verify(tradeRepo, never()).save(any());
    }

    // ── executeTrade — notional and currency derivation ───────────────────────

    @Test
    void manualTrade_cycleA_usesQuoteCurrencyForBalanceCheck() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        // pair1=EURUSD, cycle A → spentCurrency = "USD"
        // notional = 1.0801 × 10_000 = 10_801
        verify(positions).hasAvailableBalance(eq(Exchange.KRAKEN), eq("USD"), eq(1.0801 * 10_000.0));
    }

    @Test
    void manualTrade_cycleB_usesBaseCurrencyForBalanceCheck() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "BSS", MANUAL_LEGS);

        // pair1=EURUSD, cycle B → spentCurrency = "EUR"
        verify(positions).hasAvailableBalance(eq(Exchange.KRAKEN), eq("EUR"), anyDouble());
    }

    @Test
    void manualTrade_passesNotionalToRisk() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        verify(risk).check(1.0801 * 10_000.0);
    }

    // ── executeTrade — simulation ─────────────────────────────────────────────

    @Test
    void manualTrade_simulation_savesTrade_withSimulationStatus() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("SIMULATION");
        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SIMULATION");
        assertThat(saved.getLegs()).hasSize(3);
        assertThat(saved.getLegs()).allMatch(l -> "SIMULATED".equals(l.getStatus()));
    }

    @Test
    void manualTrade_simulation_preservesLegPricesAndVolumes() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var legs = captor.getValue().getLegs();
        assertThat(legs.get(0).getPair()).isEqualTo("EURUSD");
        assertThat(legs.get(0).getDirection()).isEqualTo("BUY");
        assertThat(legs.get(0).getPrice()).isEqualTo(1.0801);
        assertThat(legs.get(0).getVolume()).isEqualTo(10_000.0);
        assertThat(legs.get(2).getDirection()).isEqualTo("SELL");
    }

    @Test
    void manualTrade_simulation_doesNotCallPlaceOrderLegs() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        verify(broker, never()).placeOrderLegs(any());
    }

    @Test
    void manualTrade_simulation_incrementsExecutedAndCallsIncrementStats() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(autoTrader.getStats().executed()).isEqualTo(1);
        verify(triangleRepo).incrementStats(any(), anyDouble());
    }

    // ── executeTrade — live ───────────────────────────────────────────────────

    @Test
    void manualTrade_live_filled_savesTrade_withFilledStatus() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(false);
        when(broker.placeOrderLegs(any())).thenReturn(THREE_FILLED_LEGS);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("FILLED");
        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FILLED");
        assertThat(autoTrader.getStats().executed()).isEqualTo(1);
        verify(triangleRepo).incrementStats(any(), anyDouble());
    }

    @Test
    void manualTrade_live_cancelled_whenLegFails() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(false);
        when(broker.placeOrderLegs(any())).thenReturn(ONE_FAILED_LEG);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
        verify(triangleRepo, never()).incrementStats(any(), anyDouble());
    }

    @Test
    void manualTrade_bypassesCooldown() {
        ReflectionTestUtils.setField(autoTrader, "tradeCooldownMs", 60_000L);
        // force lastTradeCompletedMs to "just now" so attemptArbitrage would block
        ReflectionTestUtils.setField(autoTrader, "lastTradeCompletedMs",
            System.currentTimeMillis());

        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        // manual trade must succeed even though cooldown hasn't elapsed
        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("SIMULATION");
    }

    // ── profit threshold gate ─────────────────────────────────────────────────

    @Test
    void attemptArbitrage_rejected_whenProfitBelowThreshold() {
        // signal profit (0.0001) is below TRI.minProfitPercent (0.00025)
        TRI.setMinProfitPercent(0.00025);
        var lowProfitSignal = new Signal(Exchange.KRAKEN, TRI, Cycle.BBS, 0.0001, OB, OB, OB);

        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(lowProfitSignal));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());
        when(risk.checkProfit(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(RiskService.RiskResult.block("below minimum"));

        autoTrader.attemptArbitrage();

        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
        verify(broker, never()).placeOrder(any(), anyDouble());
        verify(broker, never()).computeLegs(any(), anyDouble());
    }

    @Test
    void manualTrade_rejected_whenProfitBelowThreshold() {
        TRI.setMinProfitPercent(0.00025);
        when(broker.openOrderCount()).thenReturn(0);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());
        when(risk.checkProfit(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
            .thenReturn(RiskService.RiskResult.block("below minimum"));

        var result = autoTrader.executeTrade(TRI, "BBS", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("REJECTED_PROFIT");
        verify(tradeRepo, never()).save(any());
    }

    // ── cooldown gate ─────────────────────────────────────────────────────────

    @Test
    void attemptArbitrage_skips_withinCooldown() {
        ReflectionTestUtils.setField(autoTrader, "tradeCooldownMs", 60_000L);
        ReflectionTestUtils.setField(autoTrader, "lastTradeCompletedMs",
            System.currentTimeMillis());

        when(broker.openOrderCount()).thenReturn(0);

        autoTrader.attemptArbitrage();

        verify(arbitrageEngine, never()).scanForOpportunities();
    }

    // ── computeMinimumVolume / computeLegs / computePnlFromLegs ─────────────────

    @Test
    @SuppressWarnings("unchecked")
    void computeLegs_SBS_USDTRY_EURTRY_EURUSD_orderSize500() {
        // FX rates: USD/TRY=45.37 → rate("TRY")=1/45.37, EUR/USD=1.18 → rate("EUR")=1.18
        when(currencyRateFeed.getRate("USD")).thenReturn(1.0);
        when(currencyRateFeed.getRate("EUR")).thenReturn(1.18);
        when(currencyRateFeed.getRate("TRY")).thenReturn(1.0 / 45.37);

        var triSbs = new TriangleConfig();
        triSbs.setPair1("USDTRY");
        triSbs.setPair2("EURTRY");
        triSbs.setPair3("EURUSD");
        triSbs.setExchange("KRAKEN");
        triSbs.setCycle("SBS");

        var obUsdTry = new OrderBook("USDTRY", 45.36,   500.0, 45.38, 1_000_000.0);
        var obEurTry = new OrderBook("EURTRY", 53.55, 1_000_000.0, 54.00, 1_000_000.0);
        var obEurUsd = new OrderBook("EURUSD",  1.20, 1_000_000.0,  1.22, 1_000_000.0);
        var signal = new Signal(Exchange.KRAKEN, triSbs, com.ib.arb.scanner.Cycle.SBS,
                0.432, obUsdTry, obEurTry, obEurUsd);

        // ── computeMinimumVolume ──────────────────────────────────────────────
        // SBS: min3(bidQty(USDTRY)*bid*rate(TRY), askQty(EURTRY)*ask*rate(TRY), bidQty(EURUSD)*bid*rate(USD))
        //    = min3(500*45.36*(1/45.37), 1M*54*(1/45.37), 1M*1.20*1.0)
        //    = min3(22680/45.37, ...) ≈ min3(499.89, 1_190_167, 1_200_000) → 499.89 (bottleneck: USDTRY bid qty)
        var liquidityCap = autoTrader.computeMinimumVolume(signal);
        assertThat(liquidityCap).isCloseTo(499.89, offset(0.01));

        // ── computeLegs ───────────────────────────────────────────────────────
        // volumes: USDTRY=500/rate(USD)=500, EURTRY=500/rate(TRY)=500*45.37=22685, EURUSD=500/rate(EUR)=500/1.18≈423.73
        var legs = (List<com.ib.arb.broker.OrderLeg>)
                ReflectionTestUtils.invokeMethod(autoTrader, "computeLegs", signal, 500.0);

        assertThat(legs).hasSize(3);

        var leg1 = legs.get(0);
        assertThat(leg1.legIndex()).isEqualTo(1);
        assertThat(leg1.pair()).isEqualTo("USDTRY");
        assertThat(leg1.direction()).isEqualTo("SELL");
        assertThat(leg1.price()).isEqualTo(45.36);
        assertThat(leg1.volume()).isEqualTo(500.0);          // 500 / rate("USD")=1.0

        var leg2 = legs.get(1);
        assertThat(leg2.legIndex()).isEqualTo(2);
        assertThat(leg2.pair()).isEqualTo("EURTRY");
        assertThat(leg2.direction()).isEqualTo("BUY");
        assertThat(leg2.price()).isEqualTo(54.00);
        assertThat(leg2.volume()).isCloseTo(22685.0, offset(0.01)); // 500 / rate("TRY")=500*45.37

        var leg3 = legs.get(2);
        assertThat(leg3.legIndex()).isEqualTo(3);
        assertThat(leg3.pair()).isEqualTo("EURUSD");
        assertThat(leg3.direction()).isEqualTo("SELL");
        assertThat(leg3.price()).isEqualTo(1.20);
        assertThat(leg3.volume()).isCloseTo(423.73, offset(0.01)); // 500 / rate("EUR")=500/1.18

        // ── computePnlFromLegs ────────────────────────────────────────────────
        // 500 USD → ×45.36 → 22680 TRY → ÷54.00 → 420 EUR → ×1.20 → 504 USD
        // PnL = 504 - 500 = 4.0 USD
        var pnl = (Double) ReflectionTestUtils.invokeMethod(autoTrader, "computePnlFromLegs", legs, 500.0);
        assertThat(pnl).isEqualTo(4.0, offset(0.001));
    }
}
