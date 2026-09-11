package com.ib.arb.api;

import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import java.util.List;

@RestController
@RequestMapping("/api/triangles")
public class TriangleController {

    private final TriangleConfigRepository triangleRepo;

    public TriangleController(TriangleConfigRepository triangleRepo) {
        this.triangleRepo = triangleRepo;
    }

    @GetMapping
    public ResponseEntity<List<TriangleConfig>> getAll() {
        return ResponseEntity.ok(triangleRepo.findAllByOrderByDisplayOrderAsc());
    }

    @PostMapping
    public ResponseEntity<TriangleConfig> create(@RequestBody TriangleConfig body) {
        body.setStatus("INACTIVE");
        body.setHits(0);
        body.setTotalProfitUsd(0);
        body.setDisplayOrder(triangleRepo.findMaxDisplayOrder().orElse(0) + 1);
        if (body.getStaleMs1() <= 0) body.setStaleMs1(10_000);
        if (body.getStaleMs2() <= 0) body.setStaleMs2(10_000);
        if (body.getStaleMs3() <= 0) body.setStaleMs3(10_000);
        return ResponseEntity.ok(triangleRepo.save(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TriangleConfig> update(@PathVariable("id") Long id,
                                                  @RequestBody TriangleConfig body) {
        return triangleRepo.findById(id)
            .map(existing -> {
                existing.setExchange(body.getExchange());
                existing.setPair1(body.getPair1());
                existing.setPair2(body.getPair2());
                existing.setPair3(body.getPair3());
                existing.setCycle(body.getCycle());
                existing.setMinProfitUsd(body.getMinProfitUsd());
                existing.setMinProfitPercent(body.getMinProfitPercent());
                existing.setStatus(body.getStatus());
                existing.setStaleMs1(body.getStaleMs1() > 0 ? body.getStaleMs1() : 10_000);
                existing.setStaleMs2(body.getStaleMs2() > 0 ? body.getStaleMs2() : 10_000);
                existing.setStaleMs3(body.getStaleMs3() > 0 ? body.getStaleMs3() : 10_000);
                return ResponseEntity.ok(triangleRepo.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/stale-ms/apply")
    public ResponseEntity<Void> applyStaleMs(@RequestBody Map<String, Integer> body) {
        int ms = body.getOrDefault("value", 10_000);
        if (ms <= 0) ms = 10_000;
        triangleRepo.applyStaleMs(ms);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        if (!triangleRepo.existsById(id)) return ResponseEntity.notFound().build();
        triangleRepo.deleteById(id);
        triangleRepo.renumberAll();
        return ResponseEntity.noContent().build();
    }
}
