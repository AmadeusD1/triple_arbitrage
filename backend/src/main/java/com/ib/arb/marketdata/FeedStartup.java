package com.ib.arb.marketdata;

import com.ib.arb.engine.ArbitrageEngine;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FeedStartup implements ApplicationRunner {

    private final List<OrderBookFeed> feeds;
    private final ArbitrageEngine arbitrageEngine;

    public FeedStartup(List<OrderBookFeed> feeds, ArbitrageEngine arbitrageEngine) {
        this.feeds = feeds;
        this.arbitrageEngine = arbitrageEngine;
    }

    @Override
    public void run(ApplicationArguments args) {
        var pairs = arbitrageEngine.allPairs();
        feeds.forEach(feed -> feed.subscribe(pairs));
    }
}
