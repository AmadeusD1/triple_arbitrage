package com.ib.arb.config;

import com.ib.arb.execution.AutoTrader;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.Trade;

import java.util.List;
import java.util.Map;

/**
 * Payload broadcast to all WebSocket clients after each arbitrage cycle.
 *
 * @param dailyProfitAndLoss cumulative P&L since midnight UTC
 * @param brokerConnected    {@code true} if API credentials are set (or simulation mode is on)
 * @param arbStats           in-memory counters since last restart
 * @param recentTrades       last 20 trades ordered by time descending
 * @param prices             current bid/ask snapshot for every configured pair
 * @param tradeInProgress    {@code true} while a live order combo is being placed
 * @param fxRates            latest USD rates from the currency aggregator feed
 */
public record DashboardSnapshot(
    double dailyProfitAndLoss,
    boolean brokerConnected,
    AutoTrader.ArbitrageStats arbStats,
    List<Trade> recentTrades,
    List<PriceSnapshot> prices,
    boolean tradeInProgress,
    Map<String, Double> fxRates
) {}
