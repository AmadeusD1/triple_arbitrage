package com.ib.arb.api;

import com.ib.arb.position.PositionClient.OpenOrder;
import com.ib.arb.position.PositionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OpenOrdersController {

    private final PositionService positionService;

    public OpenOrdersController(PositionService positionService) {
        this.positionService = positionService;
    }

    @GetMapping("/open")
    public ResponseEntity<List<OpenOrder>> getOpenOrders() {
        return ResponseEntity.ok(positionService.fetchOpenOrders());
    }
}
