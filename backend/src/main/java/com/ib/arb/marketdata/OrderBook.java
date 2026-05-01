package com.ib.arb.marketdata;

public record OrderBook(String pair, double bid, double ask) {

    public boolean isValid() {
        return bid > 0 && ask > 0 && ask > bid;
    }
}
