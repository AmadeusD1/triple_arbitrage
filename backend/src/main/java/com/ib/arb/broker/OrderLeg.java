package com.ib.arb.broker;

public record OrderLeg(int legIndex, String pair, String direction, double price, double quantity, String orderType) {
    public OrderLeg {
        if (orderType == null) orderType = "LIMIT";
    }
}
