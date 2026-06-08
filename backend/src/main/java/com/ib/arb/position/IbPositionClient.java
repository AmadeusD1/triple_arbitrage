package com.ib.arb.position;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.IbGatewayConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Position/balance client for IB via IB Gateway.
 * Balances are fetched via reqAccountSummary (CashBalance tag) and cached until
 * the next refresh cycle in PositionService. Open orders are read from the IB
 * callback store maintained by IbGatewayConnection.
 */
@Component
public class IbPositionClient implements PositionClient {

    private static final Logger log = LoggerFactory.getLogger(IbPositionClient.class);
    // refresh account summary if cache is older than this
    private static final long CACHE_TTL_MS = 3_000;

    private final IbGatewayConnection connection;

    public IbPositionClient(IbGatewayConnection connection) {
        this.connection = connection;
    }

    @Override
    public Exchange getExchange() { return Exchange.IB; }

    @Override
    public Map<String, Double> fetchBalances() {
        if (!connection.isConnected()) return Map.of();
        if (System.currentTimeMillis() - connection.lastBalanceTs > CACHE_TTL_MS) {
            connection.refreshAccountSummary();
            // give the async callback a moment to populate
            try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return Map.copyOf(connection.cashBalances);
    }

    @Override
    public List<OpenOrder> fetchOpenOrders() {
        if (!connection.isConnected()) return List.of();
        var orders = new ArrayList<OpenOrder>();
        connection.openOrderMap.values().forEach(o -> orders.add(new OpenOrder(
            String.valueOf(o.orderId()), o.pair(), o.action().toLowerCase(),
            o.orderType().toLowerCase(), o.price(), o.qty(), 0,
            System.currentTimeMillis() / 1000.0, o.status().toLowerCase())));
        return orders;
    }
}
