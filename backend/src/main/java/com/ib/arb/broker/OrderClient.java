package com.ib.arb.broker;

import com.ib.arb.marketdata.Exchange;

import java.util.List;
import java.util.Optional;

/**
 * Exchange-agnostic contract for order placement.
 * One implementation per supported exchange; {@code AutoTrader} routes by {@link Exchange}.
 */
public interface OrderClient {

    Exchange getExchange();

    /** {@code true} if credentials are present and the client can place orders. */
    boolean isConnected();

    /** {@code true} when the global {@code simulation_mode} setting is enabled. */
    boolean isSimulation();

    /** Number of orders currently in flight (incremented at start, decremented on completion). */
    int openOrderCount();

    /** Places orders using caller-supplied prices/volumes and returns one result per leg. */
    List<LegResult> placeOrderLegs(List<OrderLeg> legs);

    /**
     * Returns and clears a critical error (e.g. a precision/format rejection) raised by the most
     * recent {@link #placeOrderLegs} call, if any. Used by {@code AutoTrader} to decide whether
     * trading on this exchange must be halted and the user notified.
     */
    default Optional<String> consumeError() { return Optional.empty(); }

    /**
     * Refreshes the per-symbol price/quantity decimal precision used to format order requests,
     * by querying the exchange's public instruments API for the given pairs. Called by
     * {@code ExchangeManager} before an exchange's scan loop (re)starts, so newly added
     * triangle pairs get correct precision without manual edits. No-op for exchanges that
     * don't need per-symbol precision formatting.
     */
    default void warmPrecision(List<String> pairs) {}

    /**
     * Returns {@code [priceDecimals, qtyDecimals]} used to format order requests for the given pair.
     * Falls back to a conservative default for exchanges/pairs without known precision.
     */
    default int[] getPrecision(String pair) { return new int[]{8, 8}; }

    /** Cancels a previously-placed open order by its exchange-native order id. */
    default CancelResult cancelOrder(String txid, String pair) {
        return new CancelResult(false, "Cancel not supported for " + getExchange());
    }

    record CancelResult(boolean success, String message) {}
}
