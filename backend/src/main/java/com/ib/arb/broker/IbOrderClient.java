package com.ib.arb.broker;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.IbGatewayConnection;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Order client for IB (Interactive Brokers) FX via IB Gateway TWS API.
 * Places limit FX orders on IDEALPRO (interbank). Legs are submitted concurrently;
 * each waits up to 10 s for an order-accepted callback before timing out.
 */
@Component
public class IbOrderClient extends AbstractOrderClient {

    private static final Logger log = LoggerFactory.getLogger(IbOrderClient.class);

    private final IbGatewayConnection connection;

    public IbOrderClient(SettingRepository settings,
                         ExchangeConfigRepository configRepo,
                         IbGatewayConnection connection) {
        super(settings, configRepo);
        this.connection = connection;
    }

    @Override public Exchange getExchange() { return Exchange.IB; }

    @Override
    public boolean isConnected() {
        return isSimulation() || connection.isConnected();
    }

    @Override
    public List<LegResult> placeOrderLegs(List<OrderLeg> legs) {
        openOrders.incrementAndGet();
        try {
            var futures = legs.stream()
                .map(leg -> CompletableFuture.supplyAsync(() -> {
                    try {
                        var future = connection.placeOrder(
                                leg.pair(), leg.direction(), leg.price(), leg.quantity());
                        var orderId = future.get(10, TimeUnit.SECONDS);
                        boolean filled = orderId != null;
                        return new LegResult(leg.legIndex(), leg.pair(), leg.direction(),
                                leg.price(), leg.quantity(), filled, orderId);
                    } catch (Exception e) {
                        log.error("[IB] Order leg {} failed: {}", leg.legIndex(), e.getMessage());
                        return new LegResult(leg.legIndex(), leg.pair(), leg.direction(),
                                leg.price(), leg.quantity(), false, null);
                    }
                }))
                .toList();

            return futures.stream()
                .map(f -> { try { return f.join(); } catch (Exception e) {
                    return new LegResult(0, "", "", 0, 0, false, null); } })
                .sorted(Comparator.comparingInt(LegResult::legIndex))
                .toList();
        } finally {
            openOrders.decrementAndGet();
        }
    }
}
