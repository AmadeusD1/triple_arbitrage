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

        // default limit is 50_000
        assertThat(riskService.check(49_000.0).allowed()).isTrue();
        assertThat(riskService.check(51_000.0).allowed()).isFalse();
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
