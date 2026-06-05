package com.ib.arb.engine;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.OrderBookFeed;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
import com.ib.arb.scanner.Cycle;
import com.ib.arb.scanner.Signal;
import static com.ib.arb.common.Constants.TriangleStatus.ACTIVE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ArbitrageEngine {

    private static final Logger log = LoggerFactory.getLogger(ArbitrageEngine.class);

    private final List<OrderBookFeed> feeds;
    private final TriangleConfigRepository triangleRepo;

    public ArbitrageEngine(List<OrderBookFeed> feeds, TriangleConfigRepository triangleRepo) {
        this.feeds = feeds;
        this.triangleRepo = triangleRepo;
    }

    /**
     * Returns all FX pairs referenced by triangles of the given exchange.
     * Used by {@code FeedStartup} and {@code ExchangeManager} to subscribe feeds.
     */
    public List<String> pairsForExchange(Exchange exchange) {
        return triangleRepo.findAll().stream()
            .filter(t -> exchange.name().equalsIgnoreCase(t.getExchange()))
            .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
            .distinct()
            .toList();
    }

    /** All pairs across all exchanges (used for initial feed subscription on startup). */
    public List<String> allPairs() {
        return triangleRepo.findAll().stream()
            .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
            .distinct()
            .toList();
    }

    /** Live bid/ask snapshots for all configured pairs across all connected feeds. */
    public List<PriceSnapshot> currentSnapshots() {
        var pairs = triangleRepo.findAll().stream()
            .flatMap(t -> Stream.of(t.getPair1(), t.getPair2(), t.getPair3()))
            .distinct()
            .toList();
        return feeds.stream()
            .flatMap(feed -> pairs.stream()
                .map(pair -> feed.getSnapshot(pair))
                .filter(snap -> snap != null && snap.isValid())
                .map(snap -> new PriceSnapshot(
                    feed.getExchange().name(), snap.pair(), snap.bid(), snap.ask())))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Scans active triangles for the given exchange and returns the highest-profit signal,
     * or empty if no opportunity exceeds its threshold.
     */
    public Optional<Signal> scanForOpportunities(Exchange exchange) {
        var active = triangleRepo.findByStatus(ACTIVE).stream()
            .filter(t -> exchange.name().equalsIgnoreCase(t.getExchange()))
            .toList();

        var feed = feeds.stream()
            .filter(f -> f.getExchange() == exchange)
            .findFirst()
            .orElse(null);

        if (feed == null) {
            log.debug("[SCAN] No feed registered for {}", exchange);
            return Optional.empty();
        }

        log.debug("[SCAN] {} — {} active triangle(s)", exchange, active.size());

        var best = active.stream()
            .map(config -> computeEdge(feed, config))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .max(Comparator.comparingDouble(Signal::profit));

        if (best.isPresent()) {
            var s = best.get();
            log.info("[SCAN] {} best signal — triangle={} cycle={} edge={}",
                exchange, s.config().getId(), s.cycle(), String.format("%.5f", s.profit()));
        } else {
            log.debug("[SCAN] {} — no profitable opportunity", exchange);
        }
        return best;
    }

    private Optional<Signal> computeEdge(OrderBookFeed feed, TriangleConfig config) {
        var b1 = feed.getSnapshot(config.getPair1());
        var b2 = feed.getSnapshot(config.getPair2());
        var b3 = feed.getSnapshot(config.getPair3());

        if (b1 == null || b2 == null || b3 == null) {
            var missing = new ArrayList<String>();
            if (b1 == null) missing.add(config.getPair1());
            if (b2 == null) missing.add(config.getPair2());
            if (b3 == null) missing.add(config.getPair3());
            log.warn("[SCAN] Triangle={} — no snapshot for pair(s): {}", config.getId(), missing);
            return Optional.empty();
        }
        if (!b1.isValid() || !b2.isValid() || !b3.isValid()) {
            log.warn("[SCAN] Triangle={} — invalid snapshot(s)", config.getId());
            return Optional.empty();
        }

        var threshold = config.getMinProfitPercent();
        var cycle = Cycle.valueOf(config.getCycle() != null ? config.getCycle() : "BBS");

        var edge = switch (cycle) {
            case BBS -> (b3.bid() - b1.ask() * b2.ask()) / (b1.ask() * b2.ask()) * 100;
            case BSS -> (b2.bid() * b3.bid() - b1.ask()) / b1.ask() * 100;
            case BSB -> (b2.bid() - b1.ask() * b3.ask()) / (b1.ask() * b3.ask()) * 100;
            case SBS -> (b1.bid() * b3.bid() - b2.ask()) / b2.ask() * 100;
        };

        if (edge > 0 && edge > threshold) {
            log.debug("[SCAN] Triangle={} cycle={} edge={} — PROFITABLE", config.getId(), cycle, String.format("%.5f", edge));
            return Optional.of(new Signal(feed.getExchange(), config, cycle, edge, b1, b2, b3));
        }
        return Optional.empty();
    }
}
