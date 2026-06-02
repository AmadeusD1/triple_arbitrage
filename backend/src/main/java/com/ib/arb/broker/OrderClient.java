package com.ib.arb.broker;

import com.ib.arb.marketdata.Exchange;

import java.util.List;

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
}
