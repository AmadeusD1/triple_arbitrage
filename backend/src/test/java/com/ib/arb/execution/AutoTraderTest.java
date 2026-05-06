package com.ib.arb.execution;

import com.ib.arb.alert.AlertService;
import com.ib.arb.analytics.AnalyticsService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.broker.KrakenOrderClient.LegResult;
import com.ib.arb.config.DashboardWebSocketHandler;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.Trade;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.position.PositionService;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.risk.RiskService;
import com.ib.arb.scanner.ArbitrageEngine;
import com.ib.arb.scanner.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoTraderTest {

    ArbitrageEngine arbitrageEngine          = mock(ArbitrageEngine.class);
    PositionService positions                = mock(PositionService.class);
    RiskService risk                         = mock(RiskService.class);
    KrakenOrderClient broker                 = mock(KrakenOrderClient.class);
    TradeRepository tradeRepo                = mock(TradeRepository.class);
    AlertService alerts                      = mock(AlertService.class);
    AnalyticsService analytics               = mock(AnalyticsService.class);
    DashboardWebSocketHandler ws             = mock(DashboardWebSocketHandler.class);
    TriangleConfigRepository triangleRepo    = mock(TriangleConfigRepository.class);

    AutoTrader autoTrader;

    static final TriangleConfig TRI;
    static {
        TRI = new TriangleConfig();
        TRI.setPair1("EURUSD");
        TRI.setPair2("USDJPY");
        TRI.setPair3("EURJPY");
        TRI.setExchange("KRAKEN");
    }

    static final Signal SIGNAL_A = new Signal(Exchange.KRAKEN, TRI, "A", 0.001);

    static final List<LegResult> THREE_FILLED_LEGS = List.of(
        new LegResult(1, "EURUSD", "BUY",  1.0801, 92584.0, true,  "TXID-1"),
        new LegResult(2, "USDJPY", "BUY",  150.01, 666.6,   true,  "TXID-2"),
        new LegResult(3, "EURJPY", "SELL", 162.00, 617.3,   true,  "TXID-3")
    );

    static final List<LegResult> ONE_FAILED_LEG = List.of(
        new LegResult(1, "EURUSD", "BUY",  1.0801, 92584.0, true,  "TXID-1"),
        new LegResult(2, "USDJPY", "BUY",  150.01, 666.6,   false, null)
    );

    @BeforeEach
    void setup() {
        autoTrader = new AutoTrader(arbitrageEngine, positions, risk, broker,
                                    tradeRepo, alerts, analytics, ws, triangleRepo);
        ReflectionTestUtils.setField(autoTrader, "orderSizeUsd", 100_000.0);
        ReflectionTestUtils.setField(autoTrader, "maxOpenOrders", 1);
        ReflectionTestUtils.setField(autoTrader, "tradeCooldownMs", 0L);

        when(tradeRepo.findTop20ByOrderByTimeDesc()).thenReturn(List.of());
        when(analytics.dailyProfitAndLoss()).thenReturn(0.0);
        when(broker.isConnected()).thenReturn(true);
        when(arbitrageEngine.currentSnapshots()).thenReturn(List.of(
            new PriceSnapshot("KRAKEN", "EURUSD", 1.0800, 1.0801),
            new PriceSnapshot("KRAKEN", "USDJPY", 150.00, 150.01),
            new PriceSnapshot("KRAKEN", "EURJPY", 162.00, 162.10)
        ));
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
        when(broker.placeOrder(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scanForOpportunities()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("FILLED");
        assertThat(saved.getPnl()).isEqualTo(0.001 * 100_000.0, offset(0.001));
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
        when(broker.placeOrder(any(), anyDouble())).thenReturn(ONE_FAILED_LEG);
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
        when(broker.placeOrder(any(), anyDouble())).thenReturn(List.of());
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
        when(broker.placeOrder(any(), anyDouble())).thenReturn(ONE_FAILED_LEG);
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

        var sig1 = new Signal(Exchange.KRAKEN, TRI, "A", 0.001);
        var sig2 = new Signal(Exchange.KRAKEN, TRI, "A", 0.003);
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
        var signalB = new Signal(Exchange.KRAKEN, TRI, "B", 0.001);
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

    static final List<KrakenOrderClient.OrderLeg> MANUAL_LEGS = List.of(
        new KrakenOrderClient.OrderLeg(1, "EURUSD", "BUY",  1.0801, 10_000.0),
        new KrakenOrderClient.OrderLeg(2, "USDJPY", "BUY",  150.01,    72.0),
        new KrakenOrderClient.OrderLeg(3, "EURJPY", "SELL", 162.00,    67.0)
    );
    // notional = leg1.price × leg1.volume = 1.0801 × 10_000 ≈ 10_801 USD

    @Test
    void manualTrade_rejected_whenTooManyOpenOrders() {
        when(broker.openOrderCount()).thenReturn(1);

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("REJECTED_OPEN_ORDERS");
        verify(positions, never()).hasAvailableBalance(any(), any(), anyDouble());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    void manualTrade_rejected_whenBalanceInsufficient() {
        when(broker.openOrderCount()).thenReturn(0);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(false);

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("REJECTED_BALANCE");
        verify(risk, never()).check(anyDouble());
        verify(tradeRepo, never()).save(any());
    }

    @Test
    void manualTrade_rejected_whenRiskBlocked() {
        when(broker.openOrderCount()).thenReturn(0);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.block("limit"));

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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

        autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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

        autoTrader.executeTrade(TRI, "B", MANUAL_LEGS);

        // pair1=EURUSD, cycle B → spentCurrency = "EUR"
        verify(positions).hasAvailableBalance(eq(Exchange.KRAKEN), eq("EUR"), anyDouble());
    }

    @Test
    void manualTrade_passesNotionalToRisk() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

        verify(risk).check(1.0801 * 10_000.0);
    }

    // ── executeTrade — simulation ─────────────────────────────────────────────

    @Test
    void manualTrade_simulation_savesTrade_withSimulationStatus() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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

        autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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

        autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

        verify(broker, never()).placeOrderLegs(any());
    }

    @Test
    void manualTrade_simulation_incrementsExecutedAndCallsIncrementStats() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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
        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

        assertThat(result.status()).isEqualTo("SIMULATION");
    }

    // ── profit threshold gate ─────────────────────────────────────────────────

    @Test
    void attemptArbitrage_rejected_whenProfitBelowThreshold() {
        // signal profit (0.0001) is below TRI.minProfitPercent (0.00025)
        TRI.setMinProfitPercent(0.00025);
        var lowProfitSignal = new Signal(Exchange.KRAKEN, TRI, "A", 0.0001);

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

        var result = autoTrader.executeTrade(TRI, "A", MANUAL_LEGS);

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
}
