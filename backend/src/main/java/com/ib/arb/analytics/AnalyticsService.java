package com.ib.arb.analytics;

import com.ib.arb.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Computes performance analytics over the trade history.
 *
 * <p>All methods read from the {@code trades} table via {@link TradeRepository}.
 * No state is maintained in memory — every call reflects the current database contents.
 */
@Service
public class AnalyticsService {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private final TradeRepository trades;

    public AnalyticsService(TradeRepository trades) {
        this.trades = trades;
    }

    /**
     * Returns the sum of {@code pnl} for all trades executed since midnight today (UTC).
     *
     * @return total profit/loss in USD for the current day; {@code 0.0} if no trades exist
     */
    public double dailyProfitAndLoss() {
        var startOfDay = ZonedDateTime.now(CHICAGO)
            .toLocalDate().atStartOfDay(CHICAGO)
            .withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
        var sum = trades.sumPnlSince(startOfDay);
        return sum != null ? sum : 0.0;
    }

    /**
     * Returns the maximum peak-to-trough equity drawdown across all trades.
     *
     * <p>Trades are processed in insertion order. The running equity is tracked, and
     * the largest drop from any peak to any subsequent trough is returned as a
     * negative value (e.g. {@code -250.0} means the worst decline was $250).
     *
     * @return maximum drawdown in USD as a non-positive value; {@code 0.0} if no trades exist
     *         or equity never declined
     */
    public double maxDrawdown() {
        var all = trades.findAll();
        if (all.isEmpty()) return 0.0;

        var pnls = all.stream().mapToDouble(t -> t.getPnl()).toArray();
        double equity = 0, peak = 0, maxDd = 0;
        for (var p : pnls) {
            equity += p;
            if (equity > peak) peak = equity;
            var dd = equity - peak;
            if (dd < maxDd) maxDd = dd;
        }
        return maxDd;
    }

    /**
     * Returns the percentage of trades with a positive {@code pnl}.
     *
     * @return win rate in the range {@code [0.0, 100.0]}; {@code 0.0} if no trades exist
     */
    public double winRate() {
        var all = trades.findAll();
        if (all.isEmpty()) return 0.0;
        var wins = all.stream().filter(t -> t.getPnl() > 0).count();
        return (double) wins / all.size() * 100.0;
    }

    /**
     * Returns the sum of {@code pnl} for all trades since the first day of the current calendar month (UTC).
     *
     * @return total profit/loss in USD for the current month; {@code 0.0} if no trades exist
     */
    public double monthlyPnl() {
        var startOfMonth = ZonedDateTime.now(CHICAGO)
            .toLocalDate().withDayOfMonth(1).atStartOfDay(CHICAGO)
            .withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
        var sum = trades.sumPnlSince(startOfMonth);
        return sum != null ? sum : 0.0;
    }

    /**
     * Returns the cumulative equity curve as a time-ordered list of points.
     *
     * <p>Each point represents the running total of all trade P&amp;L up to and including
     * that trade. The first point equals the P&amp;L of the first trade; the last point
     * equals the all-time total P&amp;L.
     *
     * @return list of {@link EquityPoint} in chronological order; empty if no trades exist
     */
    public List<EquityPoint> equityCurve(String status) {
        var all = trades.findAll();
        double cumulative = 0;
        var result = new java.util.ArrayList<EquityPoint>();
        for (var t : all) {
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")
                    && !status.equalsIgnoreCase(t.getStatus())) continue;
            cumulative += t.getPnl();
            result.add(new EquityPoint(t.getTime().toString(), cumulative));
        }
        return result;
    }

    /**
     * A single point on the equity curve.
     *
     * @param time   ISO-8601 timestamp of the trade that produced this point
     * @param equity cumulative P&amp;L in USD at this point in time
     */
    public record EquityPoint(String time, double equity) {}
}
