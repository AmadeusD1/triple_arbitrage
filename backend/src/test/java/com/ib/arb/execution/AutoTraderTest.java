package com.ib.arb.execution;

import com.ib.arb.alert.AlertService;
import com.ib.arb.analytics.AnalyticsService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.broker.KrakenOrderClient.LegResult;
import com.ib.arb.config.DashboardWebSocketHandler;
import com.ib.arb.marketdata.Exchange;
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

        when(tradeRepo.findTop20ByOrderByTimeDesc()).thenReturn(List.of());
        when(analytics.dailyProfitAndLoss()).thenReturn(0.0);
        when(broker.isConnected()).thenReturn(true);
        when(tradeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── early exits ───────────────────────────────────────────────────────────

    @Test
    void skips_whenTooManyOpenOrders() {
        when(broker.openOrderCount()).thenReturn(1);

        autoTrader.attemptArbitrage();

        verify(arbitrageEngine, never()).scan();
    }

    @Test
    void skips_whenNoSignal() {
        when(broker.openOrderCount()).thenReturn(0);
        when(arbitrageEngine.scan()).thenReturn(Optional.empty());

        autoTrader.attemptArbitrage();

        verify(positions, never()).hasAvailableBalance(any(), any(), anyDouble());
    }

    @Test
    void incrementsMissed_whenBalanceInsufficient() {
        when(broker.openOrderCount()).thenReturn(0);
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(false);

        autoTrader.attemptArbitrage();

        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
        verify(risk, never()).check(anyDouble());
    }

    @Test
    void incrementsMissed_whenRiskBlocked() {
        when(broker.openOrderCount()).thenReturn(0);
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.block("limit"));

        autoTrader.attemptArbitrage();

        assertThat(autoTrader.getStats().missed()).isEqualTo(1);
        verify(broker, never()).placeComboOrder(any(), anyDouble());
    }

    // ── simulation mode ───────────────────────────────────────────────────────

    @Test
    void simulation_recordsFill_withoutCallingBroker() {
        when(broker.openOrderCount()).thenReturn(0);
        when(broker.isSimulation()).thenReturn(true);
        when(broker.computeLegs(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());

        autoTrader.attemptArbitrage();

        verify(broker, never()).placeComboOrder(any(), anyDouble());
        var captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepo).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("FILLED");
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
        when(broker.placeComboOrder(any(), anyDouble())).thenReturn(THREE_FILLED_LEGS);
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
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
        when(broker.placeComboOrder(any(), anyDouble())).thenReturn(ONE_FAILED_LEG);
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
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
        when(broker.placeComboOrder(any(), anyDouble())).thenReturn(List.of());
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
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
        when(broker.placeComboOrder(any(), anyDouble())).thenReturn(ONE_FAILED_LEG);
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
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
        when(arbitrageEngine.scan()).thenReturn(Optional.of(sig1), Optional.of(sig2));

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
        when(arbitrageEngine.scan()).thenReturn(Optional.of(SIGNAL_A));
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
        when(arbitrageEngine.scan()).thenReturn(Optional.of(signalB));
        when(risk.check(anyDouble())).thenReturn(RiskService.RiskResult.ok());
        when(positions.hasAvailableBalance(any(), anyString(), anyDouble())).thenReturn(true);

        autoTrader.attemptArbitrage();

        verify(positions).hasAvailableBalance(Exchange.KRAKEN, "EUR", 100_000.0);
    }
}
