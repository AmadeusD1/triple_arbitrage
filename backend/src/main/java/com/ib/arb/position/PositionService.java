package com.ib.arb.position;

import com.ib.arb.marketdata.Exchange;
import static com.ib.arb.common.Constants.Simulation.SIMULATION_BALANCE;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    private final List<PositionClient> clients;
    private final TriangleConfigRepository triangleRepo;
    private final ExchangeConfigRepository exchangeConfigRepo;
    private final Map<Exchange, Map<String, Double>> balanceCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "position-refresh");
            t.setDaemon(true);
            return t;
        });

    public PositionService(List<PositionClient> clients, TriangleConfigRepository triangleRepo,
                           ExchangeConfigRepository exchangeConfigRepo) {
        this.clients            = clients;
        this.triangleRepo       = triangleRepo;
        this.exchangeConfigRepo = exchangeConfigRepo;
    }

    /** Called once at startup to warm the balance cache for all enabled exchanges. */
    public void startupRefresh() {
        clients.stream()
            .filter(c -> {
                var cfg = exchangeConfigRepo.findByExchange(c.getExchange().name()).orElse(null);
                return cfg != null && cfg.isEnabled();
            })
            .forEach(c -> refreshBalances(c.getExchange()));
    }

    /** Reads from cache. Cache is populated by startupRefresh, pre-trade, and post-trade refreshes. */
    public boolean hasAvailableBalance(Exchange exchange, String isoCurrency, double requiredAmount) {
        if (isSimulation(exchange)) return true;
        var balances  = balanceCache.getOrDefault(exchange, Map.of());
        var available = balances.getOrDefault(toAssetKey(exchange, isoCurrency), 0.0);
        return available >= requiredAmount;
    }

    public double getAvailableAmount(Exchange exchange, String isoCurrency) {
        if (isSimulation(exchange)) return SIMULATION_BALANCE;
        var key = toAssetKey(exchange, isoCurrency);
        return balanceCache.getOrDefault(exchange, Map.of()).getOrDefault(key, 0.0);
    }

    public void refreshBalances(Exchange exchange) {
        clients.stream()
            .filter(c -> c.getExchange() == exchange)
            .findFirst()
            .ifPresent(c -> {
                var fetched = c.fetchBalances();
                if (!fetched.isEmpty()) {
                    balanceCache.put(exchange, fetched);
                    log.info("[Position] Refreshed {} ({} entries)", exchange, fetched.size());
                }
            });
    }

    /** Schedules a balance refresh after {@code delayMs} ms — used post-trade for settlement. */
    public void refreshBalancesDelayed(Exchange exchange, long delayMs) {
        refreshScheduler.schedule(() -> refreshBalances(exchange), delayMs, TimeUnit.MILLISECONDS);
    }

    public List<BalanceEntry> getBalances(Exchange exchange) {
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
        return clients.stream()
            .filter(c -> {
                var cfg = exchangeConfigRepo.findByExchange(c.getExchange().name()).orElse(null);
                return cfg != null && cfg.isEnabled();
            })
            .flatMap(c -> c.fetchOpenOrders().stream())
            .toList();
    }

    public record BalanceEntry(String exchange, String currency, String assetKey, double amount) {}

    private boolean isSimulation(Exchange exchange) {
        return exchangeConfigRepo.findByExchange(exchange.name())
            .map(c -> c.isSimulation())
            .orElse(true);
    }

    private String toAssetKey(Exchange exchange, String iso) {
        return switch (exchange) {
            case KRAKEN   -> "Z" + iso;
            case BITSTAMP -> iso.toLowerCase();
            case HTX      -> "USD".equals(iso) ? "usdt" : iso.toLowerCase();
            default       -> iso;
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
