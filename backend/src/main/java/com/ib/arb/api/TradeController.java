package com.ib.arb.api;

import com.ib.arb.model.Trade;
import com.ib.arb.model.TradeLeg;
import com.ib.arb.repository.TradeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private final TradeRepository tradeRepo;

    public TradeController(TradeRepository tradeRepo) {
        this.tradeRepo = tradeRepo;
    }

    @GetMapping
    public ResponseEntity<List<Trade>> recent() {
        return ResponseEntity.ok(tradeRepo.findTop20ByOrderByTimeDesc());
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
        List<TradeLeg> legs
    ) {
        static TradeDetail from(Trade t) {
            return new TradeDetail(t.getId(), t.getTime(), t.getDirection(),
                t.getSpread(), t.getPnl(), t.getStatus(), t.getLatencyMs(),
                t.getLegs());
        }
    }
}
