package com.ib.arb.api;

import com.ib.arb.repository.MissedOpportunityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/missed-opportunities")
public class MissedOpportunityController {

    private final MissedOpportunityRepository repo;

    public MissedOpportunityController(MissedOpportunityRepository repo) {
        this.repo = repo;
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        repo.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
