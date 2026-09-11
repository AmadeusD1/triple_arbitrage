package com.ib.arb.api;

import com.ib.arb.model.Trade;
import com.ib.arb.model.TradeLeg;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import static com.ib.arb.common.Constants.TradeStatus.SIMULATION;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private final TradeRepository tradeRepo;
    private final TriangleConfigRepository triangleRepo;

    public TradeController(TradeRepository tradeRepo, TriangleConfigRepository triangleRepo) {
        this.tradeRepo = tradeRepo;
        this.triangleRepo = triangleRepo;
    }

    @GetMapping
    public ResponseEntity<List<Trade>> recent() {
        return ResponseEntity.ok(tradeRepo.findAllByOrderByTimeDesc());
    }

    @DeleteMapping("/simulation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSimulationTrades() {
        tradeRepo.deleteLegsByStatus(SIMULATION);
        tradeRepo.deleteTradesByStatus(SIMULATION);
        triangleRepo.resetAllStats();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TradeDetail> detail(@PathVariable("id") Long id) {
        return tradeRepo.findByIdWithLegs(id)
            .map(t -> ResponseEntity.ok(TradeDetail.from(t)))
            .orElse(ResponseEntity.notFound().build());
    }

    public record TradeDetail(
        Long id, LocalDateTime time, String direction,
        double spread, double pnl, String status, double latencyMs,
        double orderSize, double expectedPnl,
        Double realProfit, Double realProfitPercent,
        Double profitPercent,
        List<TradeLeg> legs
    ) {
        static TradeDetail from(Trade t) {
            return new TradeDetail(t.getId(), t.getTime(), t.getDirection(),
                t.getSpread(), t.getPnl(), t.getStatus(), t.getLatencyMs(),
                t.getOrderSize(), t.getExpectedPnl(),
                t.getRealProfit(), t.getRealProfitPercent(),
                t.getProfitPercent(),
                t.getLegs());
        }
    }
}
