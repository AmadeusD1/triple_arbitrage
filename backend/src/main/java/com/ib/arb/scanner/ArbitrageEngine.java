package com.ib.arb.scanner;

import com.ib.arb.marketdata.OrderBookFeed;
import com.ib.arb.marketdata.PriceSnapshot;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
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

/**
 * Scans all registered exchange feeds for triangular arbitrage opportunities.
 *
 * <p>Triangles are loaded from the database on each {@link #scan()} call, so activating
 * or deactivating a triangle takes effect within the next scan cycle without a restart.
 *
 * <p>All order-book reads are non-blocking — snapshots are pulled from the in-memory
 * state maintained by each {@link OrderBookFeed}.
 */
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
     * Returns the deduplicated set of FX pairs referenced by any configured triangle,
     * regardless of active status.
     *
     * <p>Used by {@code FeedStartup} to subscribe the order book feeds to exactly
     * the pairs that may ever be scanned. Subscribing all pairs (not just active ones)
     * means activating a triangle never requires a restart.
     *
     * @return list of pair codes (e.g. {@code "EURUSD"}), no duplicates
     */
    public List<String> allPairs() {
        return triangleRepo.findAll().stream()
            .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
            .distinct()
            .toList();
    }

    /**
     * Returns a current bid/ask snapshot for every configured pair across all feeds.
     * Pairs with no valid snapshot (feed not yet connected) are omitted.
     */
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
     * Scans every registered feed against all active triangles and returns the
     * highest-profit signal found, or empty if no opportunity exceeds its threshold.
     *
     * @return the best {@link Signal} across all exchanges and triangles, or
     *         {@link Optional#empty()} if no profitable opportunity exists
     */
    public Optional<Signal> scanForOpportunities() {
        var active = triangleRepo.findByStatus(ACTIVE);
        log.debug("[SCAN] Starting — {} active triangle(s) on {} feed(s)", active.size(), feeds.size());

        var best = feeds.stream()
            .flatMap(feed -> active.stream()
                .map(config -> computeEdge(feed, config))
                .filter(Optional::isPresent)
                .map(Optional::get))
            .max(Comparator.comparingDouble(Signal::profit));

        if (best.isEmpty()) {
            log.debug("[SCAN] No profitable opportunity found");
        } else {
            var s = best.get();
            log.info("[SCAN] Best signal — triangle={} exchange={} cycle={} edge={}",
                s.config().getId(), s.exchange(), s.cycle(), String.format("%.5f", s.profit()));
        }
        return best;
    }

    /**
     * Evaluates both cycle directions for a single triangle on a single feed.
     *
     * <ul>
     *   <li><b>Cycle A</b>: edge = {@code bid1 × bid2 − ask3}</li>
     *   <li><b>Cycle B</b>: edge = {@code bid3 − ask1 × ask2}</li>
     * </ul>
     *
     * <p>Returns empty if the feed's exchange doesn't match the triangle's exchange,
     * any snapshot is missing or invalid, or neither edge exceeds the triangle's
     * {@code minProfitPercent} threshold.
     */
    private Optional<Signal> computeEdge(OrderBookFeed feed, TriangleConfig config) {
        if (!feed.getExchange().name().equalsIgnoreCase(config.getExchange())) {
            log.debug("[SCAN] Skipping triangle={} — exchange mismatch ({} vs {})",
                config.getId(), feed.getExchange().name(), config.getExchange());
            return Optional.empty();
        }

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
            var invalid = new ArrayList<String>();
            if (!b1.isValid()) invalid.add(config.getPair1());
            if (!b2.isValid()) invalid.add(config.getPair2());
            if (!b3.isValid()) invalid.add(config.getPair3());
            log.warn("[SCAN] Triangle={} — invalid snapshot for pair(s): {}", config.getId(), invalid);
            return Optional.empty();
        }

        var threshold = config.getMinProfitPercent();
        var cycle = Cycle.valueOf(config.getCycle() != null ? config.getCycle() : "BBS");

        var edge = switch (cycle) {
            case BBS -> b1.bid() * b2.bid() - b3.ask();
            case BSS -> b1.bid() - b2.ask() * b3.ask();
            case BSB -> b1.bid() * b3.bid() - b2.ask();
            case SBS -> b2.bid() - b1.ask() * b3.ask();
        };

        if (edge > threshold) {
            log.debug("[SCAN] Triangle={} cycle={} edge={} — PROFITABLE (threshold={})",
                config.getId(), cycle, String.format("%.5f", edge), threshold);
            return Optional.of(new Signal(feed.getExchange(), config, cycle, edge));
        }

        log.debug("[SCAN] Triangle={} cycle={} edge={} — below threshold {}",
            config.getId(), cycle, String.format("%.5f", edge), threshold);
        return Optional.empty();
    }
}
