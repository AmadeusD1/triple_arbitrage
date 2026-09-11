package com.ib.arb.broker;

/**
 * Per-leg outcome returned to {@code AutoTrader} after an order attempt.
 *
 * @param legIndex  position in the triangle (1, 2, or 3)
 * @param pair      FX/crypto pair code, e.g. {@code "EURUSD"} or {@code "BTCUSDT"}
 * @param direction {@code "BUY"} or {@code "SELL"}
 * @param price     limit price used
 * @param volume    quantity in base currency units
 * @param filled    {@code true} if the exchange accepted the order
 * @param orderId   exchange transaction ID; {@code null} for simulation/failed legs
 * @param rejectionReason exchange's stated reason for rejection; {@code null} on success or when unavailable
 */
public record LegResult(int legIndex, String pair, String direction,
                        double price, double volume, boolean filled, String orderId, String rejectionReason) {}
