package com.ib.arb.repository;

import com.ib.arb.model.MissedOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MissedOpportunityRepository extends JpaRepository<MissedOpportunity, Long> {
    List<MissedOpportunity> findTop1000ByOrderByTimeDesc();
}
