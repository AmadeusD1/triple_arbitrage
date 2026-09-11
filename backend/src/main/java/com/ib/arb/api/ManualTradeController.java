package com.ib.arb.api;

import com.ib.arb.broker.OrderClient;
import com.ib.arb.broker.OrderLeg;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/manual-trade")
public class ManualTradeController {

    private static final Logger log = LoggerFactory.getLogger(ManualTradeController.class);

    private final Map<Exchange, OrderClient> orderClients;
    private final ExchangeConfigRepository configRepo;
    private final TriangleConfigRepository triangleConfigRepo;

    public ManualTradeController(List<OrderClient> clients, ExchangeConfigRepository configRepo,
                                  TriangleConfigRepository triangleConfigRepo) {
        this.orderClients = clients.stream().collect(
            Collectors.toMap(OrderClient::getExchange, Function.identity()));
        this.configRepo = configRepo;
        this.triangleConfigRepo = triangleConfigRepo;
    }

    @GetMapping("/pairs/{exchange}")
    public ResponseEntity<List<String>> pairs(@PathVariable("exchange") String exchange) {
        Exchange ex;
        try { ex = Exchange.valueOf(exchange.toUpperCase()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }

        var pairs = new LinkedHashSet<String>();
        for (var t : triangleConfigRepo.findAll()) {
            if (!ex.name().equalsIgnoreCase(t.getExchange())) continue;
            if (t.getPair1() != null) pairs.add(t.getPair1());
            if (t.getPair2() != null) pairs.add(t.getPair2());
            if (t.getPair3() != null) pairs.add(t.getPair3());
        }
        return ResponseEntity.ok(List.copyOf(pairs));
    }

    @GetMapping("/precision/{exchange}/{pair}")
    public ResponseEntity<PrecisionResponse> precision(@PathVariable("exchange") String exchange,
                                                         @PathVariable("pair") String pair) {
        Exchange ex;
        try { ex = Exchange.valueOf(exchange.toUpperCase()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }

        var client = orderClients.get(ex);
        if (client == null) return ResponseEntity.notFound().build();
        var prec = client.getPrecision(pair);
        return ResponseEntity.ok(new PrecisionResponse(prec[0], prec[1]));
    }

    @PostMapping("/order")
    public ResponseEntity<ManualOrderResponse> placeOrder(@RequestBody ManualOrderRequest req) {
        Exchange exchange;
        try { exchange = Exchange.valueOf(req.exchange().toUpperCase()); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        var direction = "BID".equalsIgnoreCase(req.side()) ? "BUY" : "SELL";
        var price = req.limitPrice() == null ? 0.0 : req.limitPrice();

        var cfg = configRepo.findByExchange(exchange.name()).orElse(null);
        if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()
                || cfg.getApiSecret() == null || cfg.getApiSecret().isBlank()) {
            return ResponseEntity.ok(rejected(req, direction, price,
                "No API credentials configured for " + exchange + ". Add them in Exchange Settings."));
        }
        if (cfg.isSimulation()) {
            return ResponseEntity.ok(rejected(req, direction, price,
                "This exchange is in simulation mode. Disable simulation in Exchange Settings to place a real manual order."));
        }
        if (exchange == Exchange.HTX && "MARKET".equalsIgnoreCase(req.orderType()) && "BID".equalsIgnoreCase(req.side())) {
            return ResponseEntity.ok(rejected(req, direction, price,
                "HTX market buy orders use quote-currency cost as the amount field, not base quantity. "
                + "This combination is not supported — use a LIMIT order instead."));
        }

        var client = orderClients.get(exchange);
        if (client == null) {
            return ResponseEntity.ok(rejected(req, direction, price, "No order client available for " + exchange));
        }

        log.info("[MANUAL-TRADE] {} {} {} qty={} price={} type={}",
            exchange, direction, req.pair(), req.quantity(), price, req.orderType());

        var leg = new OrderLeg(1, req.pair(), direction, price, req.quantity(), req.orderType());
        var results = client.placeOrderLegs(List.of(leg));
        var result = results.get(0);

        return ResponseEntity.ok(new ManualOrderResponse(
            result.filled(), result.orderId(), result.pair(), result.direction(),
            result.volume(), result.price(), result.rejectionReason()));
    }

    private ManualOrderResponse rejected(ManualOrderRequest req, String direction, double price, String reason) {
        return new ManualOrderResponse(false, null, req.pair(), direction, req.quantity(), price, reason);
    }

    public record PrecisionResponse(int priceDecimals, int qtyDecimals) {}

    public record ManualOrderRequest(String exchange, String pair, String orderType, String side,
                                      double quantity, Double limitPrice) {}

    public record ManualOrderResponse(boolean success, String orderId, String pair, String direction,
                                       double quantity, Double price, String rejectionReason) {}
}
