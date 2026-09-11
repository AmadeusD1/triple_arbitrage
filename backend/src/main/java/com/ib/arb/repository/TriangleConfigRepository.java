package com.ib.arb.repository;

import com.ib.arb.model.TriangleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TriangleConfigRepository extends JpaRepository<TriangleConfig, Long> {

    List<TriangleConfig> findAllByOrderByDisplayOrderAsc();

    List<TriangleConfig> findByStatus(String status);

    @Query("SELECT MAX(t.displayOrder) FROM TriangleConfig t")
    Optional<Integer> findMaxDisplayOrder();

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE triangles AS t
        SET display_order = sub.rn
        FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY display_order) AS rn FROM triangles) AS sub
        WHERE t.id = sub.id
        """, nativeQuery = true)
    void renumberAll();

    @Modifying
    @Transactional
    @Query("UPDATE TriangleConfig t SET t.hits = t.hits + 1, t.totalProfitUsd = t.totalProfitUsd + :profit WHERE t.id = :id")
    void incrementStats(@Param("id") Long id, @Param("profit") double profit);

    @Modifying
    @Transactional
    @Query("UPDATE TriangleConfig t SET t.hits = 0, t.totalProfitUsd = 0")
    void resetAllStats();

    @Modifying
    @Transactional
    @Query("UPDATE TriangleConfig t SET t.staleMs1 = :ms, t.staleMs2 = :ms, t.staleMs3 = :ms")
    void applyStaleMs(@Param("ms") int ms);
}
