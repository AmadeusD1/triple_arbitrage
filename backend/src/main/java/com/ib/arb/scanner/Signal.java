package com.ib.arb.scanner;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.OrderBook;
import com.ib.arb.model.TriangleConfig;

public record Signal(Exchange exchange, TriangleConfig config, Cycle cycle, double profit,
                     OrderBook b1, OrderBook b2, OrderBook b3) {
}
