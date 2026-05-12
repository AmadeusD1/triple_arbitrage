package com.ib.arb.scanner;

import com.ib.arb.engine.ArbitrageEngine;
import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.OrderBook;
import com.ib.arb.marketdata.OrderBookFeed;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArbitrageEngineTest {

    private static final double THRESHOLD = 0.00025;

    private static TriangleConfig cfg(String p1, String p2, String p3) {
        return cfg(p1, p2, p3, "BBS");
    }

    private static TriangleConfig cfg(String p1, String p2, String p3, String cycle) {
        var c = new TriangleConfig();
        c.setPair1(p1);
        c.setPair2(p2);
        c.setPair3(p3);
        c.setExchange("KRAKEN");
        c.setMinProfitPercent(THRESHOLD);
        c.setStatus("ACTIVE");
        c.setCycle(cycle);
        return c;
    }

    private static final TriangleConfig EUR_USD_JPY = cfg("EURUSD", "USDJPY", "EURJPY");

    private static final List<TriangleConfig> ALL_NINE = List.of(
        cfg("EURUSD", "USDJPY", "EURJPY"),
        cfg("GBPUSD", "USDCHF", "GBPCHF"),
        cfg("EURGBP", "GBPUSD", "EURUSD"),
        cfg("AUDUSD", "USDJPY", "AUDJPY"),
        cfg("USDCAD", "CADJPY", "USDJPY"),
        cfg("EURCHF", "CHFJPY", "EURJPY"),
        cfg("AUDCAD", "CADJPY", "AUDJPY"),
        cfg("NZDUSD", "USDJPY", "NZDJPY"),
        cfg("EURUSD", "USDTRY", "EURTRY")
    );

    private ArbitrageEngine engine(TriangleConfigRepository repo, OrderBookFeed... feeds) {
        return new ArbitrageEngine(List.of(feeds), repo);
    }

    private ArbitrageEngine engine(OrderBookFeed... feeds) {
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(EUR_USD_JPY));
        when(repo.findAll()).thenReturn(List.of(EUR_USD_JPY));
        return engine(repo, feeds);
    }

    private OrderBookFeed feedWith(String pair1, double bid1, double ask1,
                                   String pair2, double bid2, double ask2,
                                   String pair3, double bid3, double ask3) {
        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        when(feed.getSnapshot(pair1)).thenReturn(new OrderBook(pair1, bid1, 1_000_000.0, ask1, 1_000_000.0));
        when(feed.getSnapshot(pair2)).thenReturn(new OrderBook(pair2, bid2, 1_000_000.0, ask2, 1_000_000.0));
        when(feed.getSnapshot(pair3)).thenReturn(new OrderBook(pair3, bid3, 1_000_000.0, ask3, 1_000_000.0));
        return feed;
    }

    // ── no signal ─────────────────────────────────────────────────────────────

    @Test
    void scan_returnsEmpty_whenNoFeeds() {
        assertThat(engine().scanForOpportunities()).isEmpty();
    }

    @Test
    void scan_returnsEmpty_whenSnapshotMissing() {
        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        when(feed.getSnapshot("EURUSD")).thenReturn(null);

        assertThat(engine(feed).scanForOpportunities()).isEmpty();
    }

    @Test
    void scan_returnsEmpty_whenEdgeBelowThreshold() {
        // edgeA = bid(EURJPY) - ask(EURUSD)*ask(USDJPY) = 162.001 - 1.0801*150.01 ≈ -0.020 < threshold
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.001, 162.01
        );
        assertThat(engine(feed).scanForOpportunities()).isEmpty();
    }

    @Test
    void scan_detectsCycleD_SBS_USDTRY_EURTRY_EURUSD() {
        // edgeD = bid(USDTRY) * bid(EURUSD) - ask(EURTRY) = 45.36 * 1.20 - 54.00 = 0.432 > 0.0001
        var tri = cfg("USDTRY", "EURTRY", "EURUSD", "SBS");
        tri.setMinProfitPercent(0.0001);
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(tri));
        when(repo.findAll()).thenReturn(List.of(tri));

        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        when(feed.getSnapshot("USDTRY")).thenReturn(new OrderBook("USDTRY", 45.36,   500.0, 45.38, 1_000_000.0));
        when(feed.getSnapshot("EURTRY")).thenReturn(new OrderBook("EURTRY", 53.55, 1_000_000.0, 54.00, 1_000_000.0));
        when(feed.getSnapshot("EURUSD")).thenReturn(new OrderBook("EURUSD",  1.20, 1_000_000.0,  1.22, 1_000_000.0));
        var signal = engine(repo, feed).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo(Cycle.SBS);
        assertThat(signal.get().profit()).isGreaterThan(0.0001);
        assertThat(signal.get().exchange()).isEqualTo(Exchange.KRAKEN);
    }

    // ── cycle A ───────────────────────────────────────────────────────────────

    @Test
    void scan_detectsCycleA_whenBidProductExceedsAsk3() {
        // edgeA = bid(EURJPY) - ask(EURUSD)*ask(USDJPY) = 162.10 - 1.0801*150.01 ≈ 0.079 > threshold
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.10, 162.20   // bid=162.10 > ask1*ask2=162.021 → Cycle A fires
        );
        var signal = engine(feed).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo(Cycle.BBS);
        assertThat(signal.get().profit()).isGreaterThan(THRESHOLD);
        assertThat(signal.get().exchange()).isEqualTo(Exchange.KRAKEN);
    }

    // ── cycle B (BUY,SELL,SELL): edgeB = bid1 − ask2×ask3 ────────────────────

    @Test
    void scan_detectsCycleB_whenBid1ExceedsAsk2xAsk3() {
        // edgeB = bid_EURGBP * bid_GBPUSD − ask_EURUSD = 0.870*1.260 − 1.081 ≈ 0.015 > threshold
        var tri = cfg("EURUSD", "EURGBP", "GBPUSD", "BSS");
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(tri));
        when(repo.findAll()).thenReturn(List.of(tri));

        var feed = feedWith(
            "EURUSD", 1.080, 1.081,
            "EURGBP", 0.870, 0.871,
            "GBPUSD", 1.260, 1.261
        );
        var signal = engine(repo, feed).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo(Cycle.BSS);
        assertThat(signal.get().profit()).isGreaterThan(THRESHOLD);
    }

    // ── cycle C (BUY,SELL,BUY): edgeC = bid1×bid3 − ask2 ─────────────────────

    @Test
    void scan_detectsCycleC_whenBid1xBid3ExceedsAsk2() {
        // edgeC = 1/ask_EURUSD * bid_EURCHF − ask_USDCHF = 1/1.086 * 0.995 − 0.911 ≈ 0.005 > threshold
        var tri = cfg("EURUSD", "EURCHF", "USDCHF", "BSB");
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(tri));
        when(repo.findAll()).thenReturn(List.of(tri));

        var feed = feedWith(
            "EURUSD", 1.085, 1.086,
            "EURCHF", 0.995, 0.996,   // bid=0.995 > ask1*ask3 = 1.086*0.911 ≈ 0.989
            "USDCHF", 0.910, 0.911
        );
        var signal = engine(repo, feed).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo(Cycle.BSB);
        assertThat(signal.get().profit()).isGreaterThan(THRESHOLD);
    }

    // ── cycle D (SELL,BUY,SELL): edgeD = bid2 − ask1×ask3 ────────────────────

    @Test
    void scan_detectsCycleD_whenBid2ExceedsAsk1xAsk3() {
        // edgeD = bid_USDCHF * bid_EURUSD − ask_EURCHF = 0.910*1.080 − 0.978 ≈ 0.005 > threshold
        var tri = cfg("USDCHF", "EURCHF", "EURUSD", "SBS");
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(tri));
        when(repo.findAll()).thenReturn(List.of(tri));

        var feed = feedWith(
            "USDCHF", 0.910, 0.911,
            "EURCHF", 0.977, 0.978,
            "EURUSD", 1.080, 1.081   // bid=1.080 → 0.910*1.080=0.9828 > ask_EURCHF=0.978
        );
        var signal = engine(repo, feed).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo(Cycle.SBS);
        assertThat(signal.get().profit()).isGreaterThan(THRESHOLD);
    }

    // ── best signal selection ─────────────────────────────────────────────────

    @Test
    void scan_returnsBestSignal_acrossMultipleFeeds() {
        // feed1: small edge, feed2: larger edge — engine must return feed2's signal
        var feed1 = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.025, 162.03  // edgeA = 162.025 - 162.021 ≈ 0.004
        );
        var feed2 = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.15, 162.20   // edgeA = 162.15 - 162.021 ≈ 0.129
        );
        when(feed2.getExchange()).thenReturn(Exchange.KRAKEN);

        var signal = engine(feed1, feed2).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().profit()).isGreaterThan(0.09);
    }

    // ── exchange filter ───────────────────────────────────────────────────────

    @Test
    void scan_returnsEmpty_whenExchangeMismatch() {
        var binanceTri = cfg("EURUSD", "USDJPY", "EURJPY");
        binanceTri.setExchange("BINANCE");

        var repo = mock(TriangleConfigRepository.class);
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(binanceTri));

        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.90  // edge would be profitable if exchange matched
        );

        assertThat(engine(repo, feed).scanForOpportunities()).isEmpty();
    }

    // ── per-triangle threshold ────────────────────────────────────────────────

    @Test
    void scan_usesPerTriangleMinProfitPercent() {
        // edge = bid(EURJPY) - ask(EURUSD)*ask(USDJPY) = 162.10 - 162.021 ≈ 0.079
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.10, 162.20
        );

        var highThreshold = cfg("EURUSD", "USDJPY", "EURJPY");
        highThreshold.setMinProfitPercent(0.5);  // edge 0.079 < 0.5 → no signal
        var repoHigh = mock(TriangleConfigRepository.class);
        when(repoHigh.findByStatus("ACTIVE")).thenReturn(List.of(highThreshold));
        assertThat(engine(repoHigh, feed).scanForOpportunities()).isEmpty();

        var lowThreshold = cfg("EURUSD", "USDJPY", "EURJPY");
        lowThreshold.setMinProfitPercent(0.00001);  // edge 0.079 > 0.00001 → signal
        var repoLow = mock(TriangleConfigRepository.class);
        when(repoLow.findByStatus("ACTIVE")).thenReturn(List.of(lowThreshold));
        assertThat(engine(repoLow, feed).scanForOpportunities()).isPresent();
    }

    @Test
    void scan_signalContainsConfig() {
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.10, 162.20
        );
        var signal = engine(feed).scanForOpportunities();

        assertThat(signal).isPresent();
        assertThat(signal.get().config()).isSameAs(EUR_USD_JPY);
    }

    // ── allPairs ──────────────────────────────────────────────────────────────

    @Test
    void allPairs_returnsDistinctPairs() {
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findAll()).thenReturn(ALL_NINE);
        var pairs = engine(repo).allPairs();

        assertThat(pairs).doesNotHaveDuplicates();
        assertThat(pairs).contains("EURUSD", "USDJPY", "EURJPY");
    }

    @Test
    void allPairs_coverAllTriangleLegs() {
        var repo = mock(TriangleConfigRepository.class);
        when(repo.findAll()).thenReturn(ALL_NINE);
        var pairs = engine(repo).allPairs();

        // every triangle must have all 3 pairs covered
        assertThat(pairs).contains(
            "EURUSD", "USDJPY", "EURJPY",
            "GBPUSD", "USDCHF", "GBPCHF",
            "EURGBP", "AUDUSD", "AUDJPY"
        );
    }

    // ── currentSnapshots ──────────────────────────────────────────────────────

    @Test
    void currentSnapshots_isEmpty_whenNoFeeds() {
        assertThat(engine().currentSnapshots()).isEmpty();
    }

    @Test
    void currentSnapshots_isEmpty_whenSnapshotNull() {
        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        when(feed.getSnapshot(anyString())).thenReturn(null);

        assertThat(engine(feed).currentSnapshots()).isEmpty();
    }

    @Test
    void currentSnapshots_omitsInvalidSnapshot() {
        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        // invalid: bid == ask
        when(feed.getSnapshot("EURUSD")).thenReturn(new OrderBook("EURUSD", 1.08, 1_000_000.0, 1.08, 1_000_000.0));
        when(feed.getSnapshot("USDJPY")).thenReturn(new OrderBook("USDJPY", 150.0, 1_000_000.0, 150.01, 1_000_000.0));
        when(feed.getSnapshot("EURJPY")).thenReturn(new OrderBook("EURJPY", 161.5, 1_000_000.0, 161.9, 1_000_000.0));

        var snapshots = engine(feed).currentSnapshots();

        assertThat(snapshots).extracting(com.ib.arb.marketdata.PriceSnapshot::pair)
            .doesNotContain("EURUSD")
            .contains("USDJPY", "EURJPY");
    }

    @Test
    void currentSnapshots_returnsCorrectFields() {
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.90
        );

        var snapshots = engine(feed).currentSnapshots();

        assertThat(snapshots).hasSize(3);
        var eur = snapshots.stream()
            .filter(s -> "EURUSD".equals(s.pair())).findFirst().orElseThrow();
        assertThat(eur.exchange()).isEqualTo("KRAKEN");
        assertThat(eur.bid()).isEqualTo(1.0800);
        assertThat(eur.ask()).isEqualTo(1.0801);
    }

    @Test
    void currentSnapshots_deduplicatesPairsSharedByMultipleTriangles() {
        var tri1 = cfg("EURUSD", "USDJPY", "EURJPY");
        var tri2 = cfg("EURUSD", "USDTRY", "EURTRY");  // EURUSD shared with tri1

        var repo = mock(TriangleConfigRepository.class);
        when(repo.findAll()).thenReturn(List.of(tri1, tri2));
        when(repo.findByStatus("ACTIVE")).thenReturn(List.of(tri1, tri2));

        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        when(feed.getSnapshot("EURUSD")).thenReturn(new OrderBook("EURUSD", 1.08, 1_000_000.0, 1.081, 1_000_000.0));
        when(feed.getSnapshot("USDJPY")).thenReturn(new OrderBook("USDJPY", 150.0, 1_000_000.0, 150.01, 1_000_000.0));
        when(feed.getSnapshot("EURJPY")).thenReturn(new OrderBook("EURJPY", 161.5, 1_000_000.0, 161.9, 1_000_000.0));
        when(feed.getSnapshot("USDTRY")).thenReturn(new OrderBook("USDTRY", 38.5, 1_000_000.0, 38.6, 1_000_000.0));
        when(feed.getSnapshot("EURTRY")).thenReturn(new OrderBook("EURTRY", 41.5, 1_000_000.0, 41.6, 1_000_000.0));

        var snapshots = engine(repo, feed).currentSnapshots();

        // EURUSD appears only once despite being in two triangles
        assertThat(snapshots.stream().filter(s -> "EURUSD".equals(s.pair())).count()).isEqualTo(1);
        assertThat(snapshots).hasSize(5); // EURUSD, USDJPY, EURJPY, USDTRY, EURTRY
    }
}
