package com.ib.arb.position;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.repository.TriangleConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates exchange balance fetching and caching across all registered {@link PositionClient}s.
 *
 * <p>One {@link PositionClient} exists per supported exchange. This service handles cache TTL,
 * currency-key translation, and routing — keeping all exchange-specific HTTP logic in the clients.
 */
@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    @Value("${kraken.position-cache-ttl-ms:2000}")
    private long cacheTtlMs;

    private final List<PositionClient> clients;
    private final TriangleConfigRepository triangleRepo;
    private final Map<Exchange, Map<String, Double>> balanceCache = new ConcurrentHashMap<>();
    private final Map<Exchange, Long> lastRefreshed = new ConcurrentHashMap<>();

    public PositionService(List<PositionClient> clients, TriangleConfigRepository triangleRepo) {
        this.clients = clients;
        this.triangleRepo = triangleRepo;
    }

    public boolean hasAvailableBalance(Exchange exchange, String isoCurrency, double requiredAmount) {
        if (isStale(exchange)) refreshBalances(exchange);
        var balances = balanceCache.getOrDefault(exchange, Map.of());
        var available = balances.getOrDefault(toAssetKey(exchange, isoCurrency), 0.0);
        var result = available >= requiredAmount;
        log.debug("[Position] Balance check {} on {}: available={} required={} ok={}", isoCurrency, exchange, available, requiredAmount, result);
        return result;
    }

    public double getAvailableAmount(Exchange exchange, String isoCurrency) {
        if (isStale(exchange)) refreshBalances(exchange);
        var balances = balanceCache.getOrDefault(exchange, Map.of());
        var key = toAssetKey(exchange, isoCurrency);
        if (!balances.containsKey(key)) {
            log.warn("[Position] Balance not found for {} on {} (key={})", isoCurrency, exchange, key);
            return 0.0;
        }
        var amount = balances.get(key);
        if (amount == 0.0) {
            log.warn("[Position] Zero balance for {} on {}", isoCurrency, exchange);
        }
        return amount;
    }

    @Scheduled(fixedDelayString = "${kraken.position-cache-ttl-ms:2000}")
    public void scheduledRefresh() {
        clients.forEach(c -> refreshBalances(c.getExchange()));
    }

    public void refreshBalances(Exchange exchange) {
        log.debug("[Position] Refreshing balances for {}", exchange);
        clients.stream()
            .filter(c -> c.getExchange() == exchange)
            .findFirst()
            .ifPresent(c -> {
                var fetched = c.fetchBalances();
                if (!fetched.isEmpty()) {
                    balanceCache.put(exchange, fetched);
                    lastRefreshed.put(exchange, System.currentTimeMillis());
                    log.info("[Position] Balances refreshed for {} ({} entries)", exchange, fetched.size());
                } else {
                    log.warn("[Position] Empty balance response from {}", exchange);
                }
            });
    }

    /** Returns balances for currencies present in configured triangles. */
    public List<BalanceEntry> getBalances(Exchange exchange) {
        if (isStale(exchange)) refreshBalances(exchange);

        var relevantKeys = triangleRepo.findAll().stream()
            .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
            .flatMap(pair -> List.of(pair.substring(0, 3), pair.substring(3)).stream())
            .distinct()
            .map(iso -> toAssetKey(exchange, iso))
            .collect(Collectors.toSet());

        return balanceCache.getOrDefault(exchange, Map.of()).entrySet().stream()
            .filter(e -> e.getValue() > 0 && relevantKeys.contains(e.getKey()))
            .map(e -> new BalanceEntry(
                exchange.name(),
                fromAssetKey(exchange, e.getKey()),
                e.getKey(),
                e.getValue()))
            .sorted(Comparator.comparing(BalanceEntry::currency))
            .toList();
    }

    /** Returns open orders for all registered exchanges. */
    public List<PositionClient.OpenOrder> fetchOpenOrders() {
        var orders = clients.stream()
            .flatMap(c -> c.fetchOpenOrders().stream())
            .toList();
        log.info("[Position] Fetched {} open order(s) across {} exchange(s)", orders.size(), clients.size());
        return orders;
    }

    public record BalanceEntry(String exchange, String currency, String krakenKey, double amount) {}

    private boolean isStale(Exchange exchange) {
        var last = lastRefreshed.get(exchange);
        return last == null || System.currentTimeMillis() - last > cacheTtlMs;
    }

    private String toAssetKey(Exchange exchange, String iso) {
        if (exchange == Exchange.KRAKEN) return "Z" + iso;
        return iso;
    }

    private String fromAssetKey(Exchange exchange, String key) {
        if (exchange == Exchange.KRAKEN) return key.length() > 1 ? key.substring(1) : key;
        return key;
    }
}
