package com.ib.arb.repository;

import com.ib.arb.model.ExchangeConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeConfigRepository extends JpaRepository<ExchangeConfig, Long> {

    Optional<ExchangeConfig> findByExchange(String exchange);

    List<ExchangeConfig> findByEnabledTrue();

    boolean existsByExchange(String exchange);
}
