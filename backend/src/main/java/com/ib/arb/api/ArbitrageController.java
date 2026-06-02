package com.ib.arb.api;

import com.ib.arb.broker.OrderLeg;
import com.ib.arb.engine.AutoTrader;
import com.ib.arb.engine.ExchangeManager;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.scheduler.ArbitrageScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.ib.arb.scanner.Cycle;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/arbitrage")
public class ArbitrageController {

    private static final Logger log = LoggerFactory.getLogger(ArbitrageController.class);

    private final ArbitrageScheduler scheduler;
    private final ExchangeManager exchangeManager;
    private final AutoTrader autoTrader;
    private final TriangleConfigRepository triangleConfigRepo;

    public ArbitrageController(ArbitrageScheduler scheduler, ExchangeManager exchangeManager,
                                AutoTrader autoTrader, TriangleConfigRepository triangleConfigRepo) {
        this.scheduler         = scheduler;
        this.exchangeManager   = exchangeManager;
        this.autoTrader        = autoTrader;
        this.triangleConfigRepo = triangleConfigRepo;
    }

    // ── Global start/stop (Dashboard) ────────────────────────────────────────

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
        return ResponseEntity.ok(new StatusResponse(
            scheduler.isRunning(),
            autoTrader.getStats(),
            exchangeManager.exchangeRunningStates()));
    }

    // ── Per-exchange start/stop (Exchange Settings tab) ───────────────────────

    @PostMapping("/exchanges/{name}/start")
    public ResponseEntity<Void> startExchange(@PathVariable("name") String name) {
        try {
            exchangeManager.startExchange(Exchange.valueOf(name.toUpperCase()));
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/exchanges/{name}/stop")
    public ResponseEntity<Void> stopExchange(@PathVariable("name") String name) {
        try {
            exchangeManager.stopExchange(Exchange.valueOf(name.toUpperCase()));
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── Manual trade ─────────────────────────────────────────────────────────

    @PostMapping("/manual-trade")
    public ResponseEntity<AutoTrader.ManualTradeResult> manualTrade(@RequestBody ManualTradeRequest req) {
        log.info("[MANUAL] triangleId={} cycle={} legs={}", req.triangleId(), req.cycle(),
            req.legs() == null ? 0 : req.legs().size());
        try { Cycle.valueOf(req.cycle()); } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (req.legs() == null || req.legs().isEmpty()) return ResponseEntity.badRequest().build();
        return triangleConfigRepo.findById(req.triangleId())
            .map(config -> ResponseEntity.ok(autoTrader.executeTrade(config, req.cycle(), req.legs())))
            .orElse(ResponseEntity.notFound().build());
    }

    public record StatusResponse(boolean running, AutoTrader.ArbitrageStats stats,
                                  Map<String, Boolean> exchangeRunning) {}
    public record ManualTradeRequest(Long triangleId, String cycle, List<OrderLeg> legs) {}
}
