package com.ib.arb.api;

import com.ib.arb.engine.ExchangeManager;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.model.ExchangeConfig;
import com.ib.arb.repository.ExchangeConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/exchanges")
public class ExchangeConfigController {

    private final ExchangeConfigRepository repo;
    private final ExchangeManager exchangeManager;

    public ExchangeConfigController(ExchangeConfigRepository repo, ExchangeManager exchangeManager) {
        this.repo            = repo;
        this.exchangeManager = exchangeManager;
    }

    @GetMapping
    public ResponseEntity<List<ExchangeConfig>> getAll() {
        return ResponseEntity.ok(repo.findAll());
    }

    @PostMapping
    public ResponseEntity<ExchangeConfig> create(@RequestBody ExchangeConfig body) {
        if (repo.existsByExchange(body.getExchange().toUpperCase()))
            return ResponseEntity.badRequest().build();
        body.setExchange(body.getExchange().toUpperCase());
        if (body.getCreatedAt() == null) body.setCreatedAt(LocalDateTime.now());
        var saved = repo.save(body);
        if (saved.isEnabled()) {
            tryActivate(saved.getExchange());
        }
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExchangeConfig> update(@PathVariable Long id,
                                                  @RequestBody ExchangeConfig body) {
        return repo.findById(id).map(existing -> {
            var wasEnabled = existing.isEnabled();
            existing.setEnabled(body.isEnabled())
                    .setSimulation(body.isSimulation())
                    .setApiKey(body.getApiKey())
                    .setApiSecret(body.getApiSecret())
                    .setApiPassphrase(body.getApiPassphrase())
                    .setWsUrl(body.getWsUrl())
                    .setOrderSizeUsd(body.getOrderSizeUsd())
                    .setPositionLimitUsd(body.getPositionLimitUsd())
                    .setMaxDailyLossUsd(body.getMaxDailyLossUsd());
            var saved = repo.save(existing);

            if (!wasEnabled && saved.isEnabled()) {
                tryActivate(saved.getExchange());
            } else if (wasEnabled && !saved.isEnabled()) {
                tryDeactivate(saved.getExchange());
            }
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        var cfg = repo.findById(id).orElse(null);
        if (cfg == null) return ResponseEntity.notFound().build();
        tryDeactivate(cfg.getExchange());
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void tryActivate(String exchangeName) {
        try { exchangeManager.activateExchange(Exchange.valueOf(exchangeName)); }
        catch (IllegalArgumentException ignored) {}
    }

    private void tryDeactivate(String exchangeName) {
        try { exchangeManager.deactivateExchange(Exchange.valueOf(exchangeName)); }
        catch (IllegalArgumentException ignored) {}
    }
}
