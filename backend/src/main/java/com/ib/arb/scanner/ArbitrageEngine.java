package com.ib.arb.scanner;

import com.ib.arb.marketdata.OrderBookFeed;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
     * Scans every registered feed against all active triangles and returns the
     * highest-profit signal found, or empty if no opportunity exceeds its threshold.
     *
     * @return the best {@link Signal} across all exchanges and triangles, or
     *         {@link Optional#empty()} if no profitable opportunity exists
     */
    public Optional<Signal> scan() {
        var active = triangleRepo.findByStatus("ACTIVE");
        return feeds.stream()
            .flatMap(feed -> active.stream()
                .map(config -> computeEdge(feed, config))
                .filter(Optional::isPresent)
                .map(Optional::get))
            .max(Comparator.comparingDouble(Signal::profit));
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
            return Optional.empty();
        }

        var b1 = feed.getSnapshot(config.getPair1());
        var b2 = feed.getSnapshot(config.getPair2());
        var b3 = feed.getSnapshot(config.getPair3());

        if (b1 == null || b2 == null || b3 == null) return Optional.empty();
        if (!b1.isValid() || !b2.isValid() || !b3.isValid()) return Optional.empty();

        var threshold = config.getMinProfitPercent();

        var edgeA = b1.bid() * b2.bid() - b3.ask();
        if (edgeA > threshold) {
            return Optional.of(new Signal(feed.getExchange(), config, "A", edgeA));
        }

        var edgeB = b3.bid() - b1.ask() * b2.ask();
        if (edgeB > threshold) {
            return Optional.of(new Signal(feed.getExchange(), config, "B", edgeB));
        }

        return Optional.empty();
    }
}
