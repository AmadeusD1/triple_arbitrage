package com.ib.arb.marketdata;

import java.util.List;

public interface OrderBookFeed {

    Exchange getExchange();

    OrderBook getSnapshot(String pair);

    void subscribe(List<String> pairs);

    boolean isConnected();

    /** Closes the WebSocket and stops automatic reconnection. */
    default void disconnect() {}

    /** Removes the snapshot for {@code pair} so only fresh market data can be used. */
    default void invalidate(String pair) {}

    /** Called by {@link com.ib.arb.engine.ExchangeManager} to register a callback that fires
     *  whenever a new snapshot is stored, enabling real-time WebSocket broadcasts. */
    default void setOnUpdate(Runnable onUpdate) {}
}
