package com.ib.arb.api;

import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.execution.AutoTrader;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.scheduler.ArbitrageScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/arbitrage")
public class ArbitrageController {

    private static final Logger log = LoggerFactory.getLogger(ArbitrageController.class);

    private final ArbitrageScheduler scheduler;
    private final AutoTrader autoTrader;
    private final TriangleConfigRepository triangleConfigRepo;

    public ArbitrageController(ArbitrageScheduler scheduler, AutoTrader autoTrader,
                                TriangleConfigRepository triangleConfigRepo) {
        this.scheduler = scheduler;
        this.autoTrader = autoTrader;
        this.triangleConfigRepo = triangleConfigRepo;
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

    @PostMapping("/manual-trade")
    public ResponseEntity<AutoTrader.ManualTradeResult> manualTrade(@RequestBody ManualTradeRequest req) {
        log.info("[MANUAL] triangleId={} pairs={} cycle={} legs={}",
            req.triangleId(),
            req.legs() == null ? "-" : req.legs().stream().map(l -> l.pair()).reduce((a, b) -> a + "/" + b).orElse("-"),
            req.cycle(), req.legs() == null ? 0 : req.legs().size());
        if (!"A".equals(req.cycle()) && !"B".equals(req.cycle()))
            return ResponseEntity.badRequest().build();
        if (req.legs() == null || req.legs().isEmpty())
            return ResponseEntity.badRequest().build();
        return triangleConfigRepo.findById(req.triangleId())
            .map(config -> ResponseEntity.ok(autoTrader.executeTrade(config, req.cycle(), req.legs())))
            .orElse(ResponseEntity.notFound().build());
    }

    public record StatusResponse(boolean running, AutoTrader.ArbitrageStats stats) {}
    public record ManualTradeRequest(Long triangleId, String cycle, List<KrakenOrderClient.OrderLeg> legs) {}
}
