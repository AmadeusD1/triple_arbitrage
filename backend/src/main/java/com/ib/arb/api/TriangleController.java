package com.ib.arb.api;

import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        return ResponseEntity.ok(triangleRepo.findAll());
    }

    @PostMapping
    public ResponseEntity<TriangleConfig> create(@RequestBody TriangleConfig body) {
        body.setHits(0);
        body.setTotalProfitUsd(0);
        return ResponseEntity.ok(triangleRepo.save(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TriangleConfig> update(@PathVariable Long id,
                                                  @RequestBody TriangleConfig body) {
        return triangleRepo.findById(id)
            .map(existing -> {
                existing.setExchange(body.getExchange());
                existing.setPair1(body.getPair1());
                existing.setPair2(body.getPair2());
                existing.setPair3(body.getPair3());
                existing.setMinProfitUsd(body.getMinProfitUsd());
                existing.setMinProfitPercent(body.getMinProfitPercent());
                existing.setStatus(body.getStatus());
                return ResponseEntity.ok(triangleRepo.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!triangleRepo.existsById(id)) return ResponseEntity.notFound().build();
        triangleRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
