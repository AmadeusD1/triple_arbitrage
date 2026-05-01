package com.ib.arb.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KrakenOrderBookFeedTest {

    @ParameterizedTest
    @CsvSource({
        "EURUSD, EUR/USD",
        "USDJPY, USD/JPY",
        "GBPCHF, GBP/CHF",
        "AUDJPY, AUD/JPY"
    })
    void toKrakenSymbol_insertsSlashAtPosition3(String pair, String expected) {
        assertThat(KrakenOrderBookFeed.toKrakenSymbol(pair)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "EUR/USD, EURUSD",
        "USD/JPY, USDJPY",
        "GBP/CHF, GBPCHF",
        "AUD/JPY, AUDJPY"
    })
    void toPair_removesSlash(String krakenSymbol, String expected) {
        assertThat(KrakenOrderBookFeed.toPair(krakenSymbol)).isEqualTo(expected);
    }

    @Test
    void toKrakenSymbol_andToPair_areInverse() {
        var original = "EURUSD";
        assertThat(KrakenOrderBookFeed.toPair(KrakenOrderBookFeed.toKrakenSymbol(original)))
            .isEqualTo(original);
    }

    @Test
    void orderBook_isValid_whenBidLessThanAsk() {
        assertThat(new OrderBook("EURUSD", 1.08, 1.081).isValid()).isTrue();
    }

    @Test
    void orderBook_isInvalid_whenBidZero() {
        assertThat(new OrderBook("EURUSD", 0.0, 1.081).isValid()).isFalse();
    }

    @Test
    void orderBook_isInvalid_whenAskZero() {
        assertThat(new OrderBook("EURUSD", 1.08, 0.0).isValid()).isFalse();
    }

    @Test
    void orderBook_isInvalid_whenBidEqualsAsk() {
        assertThat(new OrderBook("EURUSD", 1.08, 1.08).isValid()).isFalse();
    }

    @Test
    void orderBook_isInvalid_whenBidExceedsAsk() {
        assertThat(new OrderBook("EURUSD", 1.082, 1.081).isValid()).isFalse();
    }
}
