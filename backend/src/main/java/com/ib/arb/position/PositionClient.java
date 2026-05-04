package com.ib.arb.position;

import com.ib.arb.marketdata.Exchange;

import java.util.List;
import java.util.Map;

/**
 * Exchange-agnostic contract for fetching live account state.
 * One implementation per exchange; {@link PositionService} acts as the cache/routing layer.
 */
public interface PositionClient {

    Exchange getExchange();

    /** Fetches raw balances keyed by the exchange's native asset symbol. */
    Map<String, Double> fetchBalances();

    /** Fetches all currently open orders on the exchange. */
    List<OpenOrder> fetchOpenOrders();

    record OpenOrder(
        String txid, String pair, String side, String orderType,
        double price, double volume, double volumeFilled,
        double openTime, String status) {}
}
