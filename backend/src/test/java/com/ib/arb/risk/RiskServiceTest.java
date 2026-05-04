package com.ib.arb.risk;

import com.ib.arb.model.Setting;
import com.ib.arb.repository.SettingRepository;
import com.ib.arb.repository.TradeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskServiceTest {

    SettingRepository settings = mock(SettingRepository.class);
    TradeRepository trades     = mock(TradeRepository.class);
    RiskService riskService    = new RiskService(settings, trades);

    private void givenSetting(String key, double value) {
        var s = new Setting();
        s.setKey(key);
        s.setValue(value);
        when(settings.findById(key)).thenReturn(Optional.of(s));
    }

    // ── position limit ────────────────────────────────────────────────────────

    @Test
    void allows_whenOrderSizeWithinPositionLimit() {
        givenSetting("position_limit", 100_000.0);
        when(trades.sumPnlSince(any())).thenReturn(0.0);

        assertThat(riskService.check(50_000.0).allowed()).isTrue();
    }

    @Test
    void blocks_whenOrderSizeExceedsPositionLimit() {
        givenSetting("position_limit", 50_000.0);

        assertThat(riskService.check(100_000.0).allowed()).isFalse();
        assertThat(riskService.check(100_000.0).reason()).contains("Position limit");
    }

    @Test
    void usesDefaultPositionLimit_whenSettingMissing() {
        when(settings.findById(eq("position_limit"))).thenReturn(Optional.empty());
        when(settings.findById(eq("max_daily_loss"))).thenReturn(Optional.empty());
        when(trades.sumPnlSince(any())).thenReturn(0.0);

        // default limit is 10_000
        assertThat(riskService.check(9_000.0).allowed()).isTrue();
        assertThat(riskService.check(11_000.0).allowed()).isFalse();
    }

    @Test
    void allows_whenOrderSizeEqualsPositionLimit() {
        givenSetting("position_limit", 100_000.0);
        when(trades.sumPnlSince(any())).thenReturn(0.0);

        // exactly at the limit — should pass (condition is strictly greater-than)
        assertThat(riskService.check(100_000.0).allowed()).isTrue();
    }

    // ── checkProfit ───────────────────────────────────────────────────────────

    @Test
    void checkProfit_allows_whenBothThresholdsMet() {
        assertThat(riskService.checkProfit(0.01, 10.0, 0.02, 100.0).allowed()).isTrue();
    }

    @Test
    void checkProfit_blocks_whenEdgeBelowMinPercent() {
        var result = riskService.checkProfit(0.01, 10.0, 0.005, 50.0);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("below minimum");
    }

    @Test
    void checkProfit_blocks_whenUsdBelowMinUsd() {
        var result = riskService.checkProfit(0.01, 10.0, 0.02, 5.0);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("$10");
    }

    @Test
    void checkProfit_allows_whenMinUsdIsZero() {
        // minProfitUsd=0 means no USD floor — edge check only
        assertThat(riskService.checkProfit(0.01, 0.0, 0.02, 0.01).allowed()).isTrue();
    }

    @Test
    void checkProfit_allows_whenEdgeExactlyAtMinPercent() {
        // condition is strict <, so equal threshold passes
        assertThat(riskService.checkProfit(0.01, 0.0, 0.01, 100.0).allowed()).isTrue();
    }

    @Test
    void checkProfit_allows_whenUsdExactlyAtMinUsd() {
        // condition is strict <, so equal threshold passes
        assertThat(riskService.checkProfit(0.01, 10.0, 0.02, 10.0).allowed()).isTrue();
    }

    @Test
    void checkProfit_blocks_onPercent_beforeCheckingUsd() {
        // edge fails first — USD should never be evaluated
        var result = riskService.checkProfit(0.01, 10.0, 0.005, 100.0);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("below minimum");
    }

    @Test
    void checkProfit_blocks_whenEdgeNegative() {
        var result = riskService.checkProfit(0.01, 10.0, -0.001, -10.0);
        assertThat(result.allowed()).isFalse();
    }

    // ── daily loss ────────────────────────────────────────────────────────────

    @Test
    void blocks_whenDailyLossReachesLimit() {
        givenSetting("position_limit", 100_000.0);
        givenSetting("max_daily_loss", -500.0);
        when(trades.sumPnlSince(any())).thenReturn(-500.0);

        assertThat(riskService.check(1_000.0).allowed()).isFalse();
        assertThat(riskService.check(1_000.0).reason()).contains("daily loss");
    }

    @Test
    void blocks_whenDailyLossExceedsLimit() {
        givenSetting("position_limit", 100_000.0);
        givenSetting("max_daily_loss", -500.0);
        when(trades.sumPnlSince(any())).thenReturn(-750.0);

        assertThat(riskService.check(1_000.0).allowed()).isFalse();
    }

    @Test
    void allows_whenDailyLossWithinLimit() {
        givenSetting("position_limit", 100_000.0);
        givenSetting("max_daily_loss", -500.0);
        when(trades.sumPnlSince(any())).thenReturn(-499.0);

        assertThat(riskService.check(1_000.0).allowed()).isTrue();
    }

    @Test
    void allows_whenNoPnlYetToday() {
        givenSetting("position_limit", 100_000.0);
        givenSetting("max_daily_loss", -500.0);
        when(trades.sumPnlSince(any())).thenReturn(null);

        assertThat(riskService.check(1_000.0).allowed()).isTrue();
    }
}
