package com.ib.arb.marketdata;

import java.util.List;

public interface OrderBookFeed {

    Exchange getExchange();

    OrderBook getSnapshot(String pair);

    void subscribe(List<String> pairs);

    boolean isConnected();
}
