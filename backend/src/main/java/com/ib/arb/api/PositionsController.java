package com.ib.arb.api;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.position.PositionService;
import com.ib.arb.position.PositionService.BalanceEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionsController {

    private final PositionService positionService;

    public PositionsController(PositionService positionService) {
        this.positionService = positionService;
    }

    @GetMapping
    public ResponseEntity<List<BalanceEntry>> getPositions() {
        var all = Arrays.stream(Exchange.values())
            .flatMap(ex -> positionService.getBalances(ex).stream())
            .toList();
        return ResponseEntity.ok(all);
    }
}
