package com.ib.arb.marketdata;

public record OrderBook(String pair, double bid, double bidQty, double ask, double askQty, long timestamp) {

    /** Convenience constructor — stamps current time. All existing call sites use this. */
    public OrderBook(String pair, double bid, double bidQty, double ask, double askQty) {
        this(pair, bid, bidQty, ask, askQty, System.currentTimeMillis());
    }

    public boolean isValid() {
        return bid > 0 && bidQty > 0 && ask > 0 && askQty > 0 && ask > bid;
    }

    public boolean isStale(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }

    /** Returns a copy of this snapshot with timestamp refreshed to now. */
    public OrderBook touched() {
        return new OrderBook(pair, bid, bidQty, ask, askQty, System.currentTimeMillis());
    }
}
