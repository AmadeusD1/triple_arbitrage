package com.ib.arb.api;

import com.ib.arb.execution.AutoTrader;
import com.ib.arb.scheduler.ArbitrageScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/arbitrage")
public class ArbitrageController {

    private final ArbitrageScheduler scheduler;
    private final AutoTrader autoTrader;

    public ArbitrageController(ArbitrageScheduler scheduler, AutoTrader autoTrader) {
        this.scheduler = scheduler;
        this.autoTrader = autoTrader;
    }

    @PostMapping("/start")
    public ResponseEntity<Void> start() {
        scheduler.start();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        scheduler.stop();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status() {
        return ResponseEntity.ok(new StatusResponse(scheduler.isRunning(), autoTrader.getStats()));
    }

    public record StatusResponse(boolean running, AutoTrader.ArbitrageStats stats) {}
}
