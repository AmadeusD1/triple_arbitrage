package com.ib.arb.repository;

import com.ib.arb.model.TriangleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TriangleConfigRepository extends JpaRepository<TriangleConfig, Long> {

    List<TriangleConfig> findByStatus(String status);

    @Modifying
    @Transactional
    @Query("UPDATE TriangleConfig t SET t.hits = t.hits + 1, t.totalProfitUsd = t.totalProfitUsd + :profit WHERE t.id = :id")
    void incrementStats(@Param("id") Long id, @Param("profit") double profit);
}
