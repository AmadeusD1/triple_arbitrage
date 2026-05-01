package com.ib.arb.api;

import com.ib.arb.analytics.AnalyticsService;
import com.ib.arb.execution.AutoTrader;
import com.ib.arb.repository.TradeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final AnalyticsService analytics;
    private final AutoTrader autoTrader;
    private final TradeRepository tradeRepo;

    public StatsController(AnalyticsService analytics, AutoTrader autoTrader,
                           TradeRepository tradeRepo) {
        this.analytics = analytics;
        this.autoTrader = autoTrader;
        this.tradeRepo = tradeRepo;
    }

    @GetMapping("/daily-pnl")
    public ResponseEntity<Map<String, Double>> dailyProfitAndLoss() {
        return ResponseEntity.ok(Map.of("dailyProfitAndLoss", analytics.dailyProfitAndLoss()));
    }

    @GetMapping("/drawdown")
    public ResponseEntity<Map<String, Double>> drawdown() {
        return ResponseEntity.ok(Map.of("drawdown", analytics.maxDrawdown()));
    }

    @GetMapping("/win-rate")
    public ResponseEntity<Map<String, Double>> winRate() {
        return ResponseEntity.ok(Map.of("winRate", analytics.winRate()));
    }

    @GetMapping("/sharpe")
    public ResponseEntity<Map<String, Double>> sharpe() {
        return ResponseEntity.ok(Map.of("sharpe", analytics.sharpe()));
    }

    @GetMapping("/arb")
    public ResponseEntity<AutoTrader.ArbitrageStats> arbStats() {
        return ResponseEntity.ok(autoTrader.getStats());
    }

    @GetMapping("/execution")
    public ResponseEntity<Map<String, Object>> executionStats() {
        var trades = tradeRepo.findAll();
        if (trades.isEmpty()) return ResponseEntity.ok(Map.of(
            "avgLatency", 0.0, "maxLatency", 0.0, "fillRate", 0.0
        ));

        var avgLatency = trades.stream().mapToDouble(t -> t.getLatencyMs()).average().orElse(0);
        var maxLatency = trades.stream().mapToDouble(t -> t.getLatencyMs()).max().orElse(0);
        var fillRate = trades.stream().filter(t -> "FILLED".equals(t.getStatus())).count()
            * 100.0 / trades.size();

        return ResponseEntity.ok(Map.of(
            "avgLatency", avgLatency,
            "maxLatency", maxLatency,
            "fillRate", fillRate
        ));
    }

    @GetMapping("/equity")
    public ResponseEntity<List<AnalyticsService.EquityPoint>> equity() {
        return ResponseEntity.ok(analytics.equityCurve());
    }
}
