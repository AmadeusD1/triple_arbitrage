package com.ib.arb.scanner;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.model.TriangleConfig;

public record Signal(Exchange exchange, TriangleConfig config, Cycle cycle, double profit) {
}
