package com.ib.arb.api;

import com.ib.arb.broker.OrderClient;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.position.PositionClient.OpenOrder;
import com.ib.arb.position.PositionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OpenOrdersController {

    private static final Logger log = LoggerFactory.getLogger(OpenOrdersController.class);

    private final PositionService positionService;
    private final Map<Exchange, OrderClient> orderClients;

    public OpenOrdersController(PositionService positionService, List<OrderClient> clients) {
        this.positionService = positionService;
        this.orderClients = clients.stream().collect(
            Collectors.toMap(OrderClient::getExchange, Function.identity()));
    }

    @GetMapping("/open")
    public ResponseEntity<List<OpenOrder>> getOpenOrders() {
        return ResponseEntity.ok(positionService.fetchOpenOrders());
    }

    @PostMapping("/cancel")
    public ResponseEntity<CancelOrderResponse> cancelOrder(@RequestBody CancelOrderRequest req) {
        Exchange exchange;
        try { exchange = Exchange.valueOf(req.exchange().toUpperCase()); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        var client = orderClients.get(exchange);
        if (client == null) {
            return ResponseEntity.ok(new CancelOrderResponse(false, "No order client available for " + exchange));
        }

        log.info("[CANCEL-ORDER] {} txid={} pair={}", exchange, req.txid(), req.pair());
        var result = client.cancelOrder(req.txid(), req.pair());
        if (!result.success()) {
            log.warn("[CANCEL-ORDER] {} txid={} failed: {}", exchange, req.txid(), result.message());
        }
        return ResponseEntity.ok(new CancelOrderResponse(result.success(), result.message()));
    }

    public record CancelOrderRequest(String exchange, String txid, String pair) {}

    public record CancelOrderResponse(boolean success, String message) {}
}
