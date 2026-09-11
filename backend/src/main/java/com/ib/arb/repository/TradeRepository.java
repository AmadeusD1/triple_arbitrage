package com.ib.arb.repository;

import com.ib.arb.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findTop20ByOrderByTimeDesc();

    List<Trade> findAllByOrderByTimeDesc();

    List<Trade> findByTimeAfter(LocalDateTime since);

    @Query(value = "SELECT SUM(pnl) FROM trades WHERE time >= :since AND pnl != 'NaN'::double precision", nativeQuery = true)
    Double sumPnlSince(@Param("since") LocalDateTime since);

    @Query(value = "SELECT SUM(pnl) FROM trades WHERE time >= :since AND exchange = :exchange AND pnl != 'NaN'::double precision", nativeQuery = true)
    Double sumPnlSinceForExchange(@Param("since") LocalDateTime since, @Param("exchange") String exchange);

    @Query("SELECT t FROM Trade t LEFT JOIN FETCH t.legs WHERE t.id = :id")
    Optional<Trade> findByIdWithLegs(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM trade_legs WHERE trade_id IN (SELECT id FROM trades WHERE status = :status)", nativeQuery = true)
    void deleteLegsByStatus(@Param("status") String status);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM trades WHERE status = :status", nativeQuery = true)
    void deleteTradesByStatus(@Param("status") String status);
}
