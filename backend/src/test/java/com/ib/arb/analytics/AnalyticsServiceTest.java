package com.ib.arb.analytics;

import com.ib.arb.model.Trade;
import com.ib.arb.repository.TradeRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    TradeRepository tradeRepo   = mock(TradeRepository.class);
    AnalyticsService analytics  = new AnalyticsService(tradeRepo);

    private Trade trade(double pnl) {
        var t = new Trade();
        t.setTime(LocalDateTime.now());
        t.setDirection("A");
        t.setSpread(0.001);
        t.setPnl(pnl);
        t.setStatus("FILLED");
        t.setLatencyMs(10);
        return t;
    }

    // ── dailyProfitAndLoss ────────────────────────────────────────────────────

    @Test
    void dailyPnl_returnsZero_whenNoTrades() {
        when(tradeRepo.sumPnlSince(any())).thenReturn(null);
        assertThat(analytics.dailyProfitAndLoss()).isEqualTo(0.0);
    }

    @Test
    void dailyPnl_returnsRepoSum() {
        when(tradeRepo.sumPnlSince(any())).thenReturn(42.50);
        assertThat(analytics.dailyProfitAndLoss()).isEqualTo(42.50);
    }

    @Test
    void dailyPnl_returnsNegativeSum() {
        when(tradeRepo.sumPnlSince(any())).thenReturn(-123.75);
        assertThat(analytics.dailyProfitAndLoss()).isEqualTo(-123.75);
    }

    // ── maxDrawdown ───────────────────────────────────────────────────────────

    @Test
    void maxDrawdown_returnsZero_whenNoTrades() {
        when(tradeRepo.findAll()).thenReturn(List.of());
        assertThat(analytics.maxDrawdown()).isEqualTo(0.0);
    }

    @Test
    void maxDrawdown_returnsZero_whenEquityOnlyRises() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(10), trade(20), trade(5)));
        assertThat(analytics.maxDrawdown()).isEqualTo(0.0);
    }

    @Test
    void maxDrawdown_calculatesCorrectly() {
        // equity: 10, 5, 2 → peak always 10 → max dd = 2 - 10 = -8
        when(tradeRepo.findAll()).thenReturn(List.of(trade(10), trade(-5), trade(-3)));
        assertThat(analytics.maxDrawdown()).isEqualTo(-8.0, offset(0.0001));
    }

    @Test
    void maxDrawdown_picksLargestDrop() {
        // equity: 10, 5, 15, 3 → peak at 10 → dd = -5; peak at 15 → dd = -12
        when(tradeRepo.findAll()).thenReturn(List.of(trade(10), trade(-5), trade(10), trade(-12)));
        assertThat(analytics.maxDrawdown()).isEqualTo(-12.0, offset(0.0001));
    }

    // ── winRate ───────────────────────────────────────────────────────────────

    @Test
    void winRate_returnsZero_whenNoTrades() {
        when(tradeRepo.findAll()).thenReturn(List.of());
        assertThat(analytics.winRate()).isEqualTo(0.0);
    }

    @Test
    void winRate_returnsHundred_whenAllPositive() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(1), trade(2), trade(3)));
        assertThat(analytics.winRate()).isEqualTo(100.0);
    }

    @Test
    void winRate_returnsZero_whenAllNegative() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(-1), trade(-2)));
        assertThat(analytics.winRate()).isEqualTo(0.0);
    }

    @Test
    void winRate_calculatesCorrectly() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(1), trade(-1), trade(1), trade(-1)));
        assertThat(analytics.winRate()).isEqualTo(50.0);
    }

    // ── sharpe ────────────────────────────────────────────────────────────────

    @Test
    void sharpe_returnsZero_whenNoTrades() {
        when(tradeRepo.findAll()).thenReturn(List.of());
        assertThat(analytics.sharpe()).isEqualTo(0.0);
    }

    @Test
    void sharpe_returnsZero_whenAllPnlIdentical() {
        // std = 0 → sharpe = 0
        when(tradeRepo.findAll()).thenReturn(List.of(trade(1), trade(1), trade(1)));
        assertThat(analytics.sharpe()).isEqualTo(0.0);
    }

    @Test
    void sharpe_isPositive_whenMeanPositive() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(3), trade(5)));
        assertThat(analytics.sharpe()).isGreaterThan(0);
    }

    @Test
    void sharpe_isNegative_whenMeanNegative() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(-3), trade(-5)));
        assertThat(analytics.sharpe()).isLessThan(0);
    }

    // ── equityCurve ───────────────────────────────────────────────────────────

    @Test
    void equityCurve_isEmpty_whenNoTrades() {
        when(tradeRepo.findAll()).thenReturn(List.of());
        assertThat(analytics.equityCurve()).isEmpty();
    }

    @Test
    void equityCurve_buildsCumulativeValues() {
        when(tradeRepo.findAll()).thenReturn(List.of(trade(10), trade(-3), trade(5)));

        var curve = analytics.equityCurve();

        assertThat(curve).hasSize(3);
        assertThat(curve.get(0).equity()).isEqualTo(10.0);
        assertThat(curve.get(1).equity()).isEqualTo(7.0);
        assertThat(curve.get(2).equity()).isEqualTo(12.0);
    }
}
