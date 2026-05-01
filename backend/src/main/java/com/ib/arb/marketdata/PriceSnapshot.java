package com.ib.arb.marketdata;

public record PriceSnapshot(String exchange, String pair, double bid, double ask) {}
