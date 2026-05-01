package com.ib.arb.scanner;

import com.ib.arb.marketdata.Exchange;
import com.ib.arb.marketdata.OrderBook;
import com.ib.arb.marketdata.OrderBookFeed;
import com.ib.arb.model.TriangleConfig;
import com.ib.arb.repository.TriangleConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArbitrageEngineTest {

    private static final double THRESHOLD = 0.00025;

    private static TriangleConfig cfg(String p1, String p2, String p3) {
        var c = new TriangleConfig();
        c.setPair1(p1);
        c.setPair2(p2);
        c.setPair3(p3);
        c.setExchange("KRAKEN");
        c.setMinProfitPercent(THRESHOLD);
        c.setStatus("ACTIVE");
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
        when(feed.getSnapshot(pair1)).thenReturn(new OrderBook(pair1, bid1, ask1));
        when(feed.getSnapshot(pair2)).thenReturn(new OrderBook(pair2, bid2, ask2));
        when(feed.getSnapshot(pair3)).thenReturn(new OrderBook(pair3, bid3, ask3));
        return feed;
    }

    // ── no signal ─────────────────────────────────────────────────────────────

    @Test
    void scan_returnsEmpty_whenNoFeeds() {
        assertThat(engine().scan()).isEmpty();
    }

    @Test
    void scan_returnsEmpty_whenSnapshotMissing() {
        var feed = mock(OrderBookFeed.class);
        when(feed.getExchange()).thenReturn(Exchange.KRAKEN);
        when(feed.getSnapshot("EURUSD")).thenReturn(null);

        assertThat(engine(feed).scan()).isEmpty();
    }

    @Test
    void scan_returnsEmpty_whenEdgeBelowThreshold() {
        // edgeA = 1.08 * 150 - 162.001 = -0.001 < threshold
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.001, 162.01
        );
        assertThat(engine(feed).scan()).isEmpty();
    }

    // ── cycle A ───────────────────────────────────────────────────────────────

    @Test
    void scan_detectsCycleA_whenBidProductExceedsAsk3() {
        // edgeA = bid(EURUSD) * bid(USDJPY) - ask(EURJPY) = 1.08 * 150 - 161.90 = 0.10
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.90   // bid < ask (valid); ask=161.90 < 162.0 → Cycle A fires
        );
        var signal = engine(feed).scan();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo("A");
        assertThat(signal.get().profit()).isGreaterThan(THRESHOLD);
        assertThat(signal.get().exchange()).isEqualTo(Exchange.KRAKEN);
    }

    // ── cycle B ───────────────────────────────────────────────────────────────

    @Test
    void scan_detectsCycleB_whenBid3ExceedsAskProduct() {
        // edgeB = 162.2 - 1.0801 * 150.01 = 162.2 - 162.03 = 0.17 >> threshold
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 162.20, 162.30
        );
        var signal = engine(feed).scan();

        assertThat(signal).isPresent();
        assertThat(signal.get().cycle()).isEqualTo("B");
        assertThat(signal.get().profit()).isGreaterThan(THRESHOLD);
    }

    // ── best signal selection ─────────────────────────────────────────────────

    @Test
    void scan_returnsBestSignal_acrossMultipleFeeds() {
        // feed1: small edge ≈ 0.001, feed2: large edge ≈ 0.1
        var feed1 = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.999  // edgeA = 162.0 - 161.999 ≈ 0.001
        );
        var feed2 = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.90   // edgeA = 162.0 - 161.90 ≈ 0.1
        );
        when(feed2.getExchange()).thenReturn(Exchange.KRAKEN);

        var signal = engine(feed1, feed2).scan();

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

        assertThat(engine(repo, feed).scan()).isEmpty();
    }

    // ── per-triangle threshold ────────────────────────────────────────────────

    @Test
    void scan_usesPerTriangleMinProfitPercent() {
        // edge = 1.08 * 150 - 161.90 = 0.10
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.90
        );

        var highThreshold = cfg("EURUSD", "USDJPY", "EURJPY");
        highThreshold.setMinProfitPercent(0.5);  // edge 0.10 < 0.5 → no signal
        var repoHigh = mock(TriangleConfigRepository.class);
        when(repoHigh.findByStatus("ACTIVE")).thenReturn(List.of(highThreshold));
        assertThat(engine(repoHigh, feed).scan()).isEmpty();

        var lowThreshold = cfg("EURUSD", "USDJPY", "EURJPY");
        lowThreshold.setMinProfitPercent(0.00001);  // edge 0.10 > 0.00001 → signal
        var repoLow = mock(TriangleConfigRepository.class);
        when(repoLow.findByStatus("ACTIVE")).thenReturn(List.of(lowThreshold));
        assertThat(engine(repoLow, feed).scan()).isPresent();
    }

    @Test
    void scan_signalContainsConfig() {
        var feed = feedWith(
            "EURUSD", 1.0800, 1.0801,
            "USDJPY", 150.00, 150.01,
            "EURJPY", 161.50, 161.90
        );
        var signal = engine(feed).scan();

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
}
