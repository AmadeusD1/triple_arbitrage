package com.ib.arb.position;

import com.ib.arb.marketdata.Exchange;
import static com.ib.arb.common.Constants.Simulation.SIMULATION_BALANCE;
import static com.ib.arb.common.Constants.Simulation.SIMULATION_MODE_KEY;
import com.ib.arb.repository.SettingRepository;
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

@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    @Value("${kraken.position-cache-ttl-ms:2000}")
    private long cacheTtlMs;

    private final List<PositionClient> clients;
    private final TriangleConfigRepository triangleRepo;
    private final SettingRepository settingRepo;
    private final Map<Exchange, Map<String, Double>> balanceCache  = new ConcurrentHashMap<>();
    private final Map<Exchange, Long>                lastRefreshed = new ConcurrentHashMap<>();

    public PositionService(List<PositionClient> clients, TriangleConfigRepository triangleRepo,
                           SettingRepository settingRepo) {
        this.clients     = clients;
        this.triangleRepo = triangleRepo;
        this.settingRepo  = settingRepo;
    }

    public boolean hasAvailableBalance(Exchange exchange, String isoCurrency, double requiredAmount) {
        if (isSimulation()) return true;
        if (isStale(exchange)) refreshBalances(exchange);
        var balances  = balanceCache.getOrDefault(exchange, Map.of());
        var available = balances.getOrDefault(toAssetKey(exchange, isoCurrency), 0.0);
        return available >= requiredAmount;
    }

    public double getAvailableAmount(Exchange exchange, String isoCurrency) {
        if (isSimulation()) return SIMULATION_BALANCE;
        if (isStale(exchange)) refreshBalances(exchange);
        var key = toAssetKey(exchange, isoCurrency);
        return balanceCache.getOrDefault(exchange, Map.of()).getOrDefault(key, 0.0);
    }

    @Scheduled(fixedDelayString = "${kraken.position-cache-ttl-ms:2000}")
    public void scheduledRefresh() {
        clients.forEach(c -> refreshBalances(c.getExchange()));
    }

    public void refreshBalances(Exchange exchange) {
        clients.stream()
            .filter(c -> c.getExchange() == exchange)
            .findFirst()
            .ifPresent(c -> {
                var fetched = c.fetchBalances();
                if (!fetched.isEmpty()) {
                    balanceCache.put(exchange, fetched);
                    lastRefreshed.put(exchange, System.currentTimeMillis());
                    log.info("[Position] Refreshed {} ({} entries)", exchange, fetched.size());
                }
            });
    }

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
            .map(e -> new BalanceEntry(exchange.name(), fromAssetKey(exchange, e.getKey()), e.getKey(), e.getValue()))
            .sorted(Comparator.comparing(BalanceEntry::currency))
            .toList();
    }

    public List<PositionClient.OpenOrder> fetchOpenOrders() {
        return clients.stream().flatMap(c -> c.fetchOpenOrders().stream()).toList();
    }

    public record BalanceEntry(String exchange, String currency, String assetKey, double amount) {}

    private boolean isSimulation() {
        return settingRepo.findById(SIMULATION_MODE_KEY).map(s -> s.getValue() == 1.0).orElse(true);
    }

    private boolean isStale(Exchange exchange) {
        var last = lastRefreshed.get(exchange);
        return last == null || System.currentTimeMillis() - last > cacheTtlMs;
    }

    private String toAssetKey(Exchange exchange, String iso) {
        return switch (exchange) {
            case KRAKEN            -> "Z" + iso;
            case BITSTAMP, HTX     -> iso.toLowerCase();
            default                -> iso;
        };
    }

    private String fromAssetKey(Exchange exchange, String key) {
        return switch (exchange) {
            case KRAKEN        -> key.length() > 1 ? key.substring(1) : key;
            case BITSTAMP, HTX -> key.toUpperCase();
            default            -> key;
        };
    }
}
