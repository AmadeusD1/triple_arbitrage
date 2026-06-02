package com.ib.arb.repository;

import com.ib.arb.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("SELECT SUM(t.pnl) FROM Trade t WHERE t.time >= :since")
    Double sumPnlSince(@Param("since") LocalDateTime since);

    @Query("SELECT SUM(t.pnl) FROM Trade t WHERE t.time >= :since AND t.exchange = :exchange")
    Double sumPnlSinceForExchange(@Param("since") LocalDateTime since, @Param("exchange") String exchange);

    @Query("SELECT t FROM Trade t LEFT JOIN FETCH t.legs WHERE t.id = :id")
    Optional<Trade> findByIdWithLegs(@Param("id") Long id);

    @Transactional
    void deleteByStatus(String status);
}
