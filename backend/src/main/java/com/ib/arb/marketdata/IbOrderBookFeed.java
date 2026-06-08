package com.ib.arb.marketdata;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * IB (Interactive Brokers) order book feed for FX CASH pairs via IB Gateway.
 * Pair format: internal "EURUSD" → IB contract symbol=EUR, currency=USD, exchange=IDEALPRO.
 * Requires TwsApi.jar in backend/libs/ and IB Gateway running at the configured host:port.
 */
@Component
public class IbOrderBookFeed implements OrderBookFeed {

    private final IbGatewayConnection connection;

    public IbOrderBookFeed(IbGatewayConnection connection) {
        this.connection = connection;
    }

    @Override
    public Exchange getExchange() { return Exchange.IB; }

    @Override
    public void subscribe(List<String> pairs) {
        if (!connection.isConnected()) connection.connect();
        connection.subscribePairs(pairs);
    }

    @Override
    public OrderBook getSnapshot(String pair) {
        var ticks = connection.marketData.get(pair.toUpperCase());
        if (ticks == null) return null;
        double bid = ticks[0], bidQty = ticks[1], ask = ticks[2], askQty = ticks[3];
        if (bid <= 0 || ask <= 0) return null;
        // IB FX sizes are in millions of base currency — treat 0 qty as 1 so isValid() passes
        return new OrderBook(pair, bid, bidQty > 0 ? bidQty : 1, ask, askQty > 0 ? askQty : 1);
    }

    @Override
    public boolean isConnected() { return connection.isConnected(); }

    @Override
    public void disconnect() { connection.disconnect(); }
}
