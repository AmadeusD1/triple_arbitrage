package com.ib.arb.risk;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Enforces pre-order risk controls.
 * Position limit and max-daily-loss are now per-exchange, read from {@code exchange_configs}.
 */
@Service
public class RiskService {

    private static final double DEFAULT_POSITION_LIMIT =  10_000.0;
    private static final double DEFAULT_MAX_DAILY_LOSS  = -1_000.0;

    private final ExchangeConfigRepository configRepo;
    private final TradeRepository trades;

    public RiskService(ExchangeConfigRepository configRepo, TradeRepository trades) {
        this.configRepo = configRepo;
        this.trades = trades;
    }

    /**
     * Runs position-limit and daily-loss checks for the given exchange.
     * Settings are read from {@code exchange_configs} on every call so UI changes
     * take effect without a restart.
     */
    public RiskResult check(Exchange exchange, double orderSize) {
        var cfg = configRepo.findByExchange(exchange.name()).orElse(null);
        var posLimit    = cfg != null ? cfg.getPositionLimitUsd() : DEFAULT_POSITION_LIMIT;
        var maxDailyLoss = cfg != null ? cfg.getMaxDailyLossUsd() : DEFAULT_MAX_DAILY_LOSS;

        if (orderSize > posLimit)
            return RiskResult.block("Position limit exceeded");

        var startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        var dailyPnl   = trades.sumPnlSinceForExchange(startOfDay, exchange.name());
        if (dailyPnl != null && dailyPnl <= maxDailyLoss)
            return RiskResult.block("Max daily loss reached");

        return RiskResult.ok();
    }

    /**
     * Validates estimated profit against a triangle's per-configured thresholds.
     */
    public RiskResult checkProfit(double minProfitPercent, double minProfitUsd,
                                   double edgePercent, double estimatedProfitUsd) {
        if (edgePercent < minProfitPercent)
            return RiskResult.block("Edge %.5f below minimum %.5f"
                .formatted(edgePercent, minProfitPercent));
        if (minProfitUsd > 0 && estimatedProfitUsd < minProfitUsd)
            return RiskResult.block("Estimated profit $%.2f below minimum $%.2f"
                .formatted(estimatedProfitUsd, minProfitUsd));
        return RiskResult.ok();
    }

    public record RiskResult(boolean allowed, String reason) {
        public static RiskResult ok()                  { return new RiskResult(true, null); }
        public static RiskResult block(String reason)  { return new RiskResult(false, reason); }
    }
}
