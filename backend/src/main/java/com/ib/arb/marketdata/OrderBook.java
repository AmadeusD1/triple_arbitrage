package com.ib.arb.marketdata;

public record OrderBook(String pair, double bid, double bidQty, double ask, double askQty) {

    public boolean isValid() {
        return bid > 0 && bidQty > 0 && ask > 0 && askQty > 0 && ask > bid;
    }
}
