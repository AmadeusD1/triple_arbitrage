package com.ib.arb.risk;

import com.ib.arb.repository.SettingRepository;
import com.ib.arb.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Enforces pre-order risk controls before any trade is placed.
 *
 * <p>Two hard-stops are checked on every cycle, both driven by the {@code settings} table
 * so they can be adjusted at runtime via the Settings UI without restarting the app:
 * <ol>
 *   <li><b>Position limit</b> — rejects orders larger than {@code position_limit} USD
 *       (default: 50,000).</li>
 *   <li><b>Daily loss limit</b> — halts trading for the rest of the day once cumulative
 *       P&amp;L since midnight falls to or below {@code max_daily_loss} USD (default: −1,000).
 *       </li>
 * </ol>
 */
@Service
public class RiskService {

    private final SettingRepository settings;
    private final TradeRepository trades;

    public RiskService(SettingRepository settings, TradeRepository trades) {
        this.settings = settings;
        this.trades = trades;
    }

    /**
     * Runs all risk checks for the proposed order size.
     *
     * <p>Checks are evaluated in order; the first failure is returned immediately.
     * Settings are read from the database on every call so runtime changes take effect
     * without a restart.
     *
     * @param orderSize notional order size in USD
     * @return {@link RiskResult#ok()} if all checks pass, or
     *         {@link RiskResult#block(String)} with a human-readable reason if any check fails
     */
    public RiskResult check(double orderSize) {
        var posLimit = getSetting("position_limit", 50_000.0);
        var maxDailyLoss = getSetting("max_daily_loss", -1_000.0);

        if (orderSize > posLimit) return RiskResult.block("Position limit exceeded");

        var startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        var dailyPnl = trades.sumPnlSince(startOfDay);
        if (dailyPnl != null && dailyPnl <= maxDailyLoss) return RiskResult.block("Max daily loss reached");

        return RiskResult.ok();
    }

    private double getSetting(String key, double defaultValue) {
        return settings.findById(key)
            .map(s -> s.getValue())
            .orElse(defaultValue);
    }

    /**
     * Outcome of a risk check.
     *
     * @param allowed {@code true} if the order may proceed
     * @param reason  human-readable explanation when {@code allowed} is {@code false};
     *                {@code null} when {@code allowed} is {@code true}
     */
    public record RiskResult(boolean allowed, String reason) {
        /** Returns a passing result. */
        public static RiskResult ok() { return new RiskResult(true, null); }

        /**
         * Returns a blocking result with the given reason.
         *
         * @param reason explanation of why the order was blocked
         */
        public static RiskResult block(String reason) { return new RiskResult(false, reason); }
    }
}
