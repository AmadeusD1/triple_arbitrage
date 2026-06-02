package com.ib.arb.marketdata;

import com.ib.arb.repository.TriangleConfigRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FeedStartup implements ApplicationRunner {

    private final List<OrderBookFeed> feeds;
    private final TriangleConfigRepository triangleRepo;

    public FeedStartup(List<OrderBookFeed> feeds, TriangleConfigRepository triangleRepo) {
        this.feeds        = feeds;
        this.triangleRepo = triangleRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var feed : feeds) {
            var pairs = triangleRepo.findAll().stream()
                .filter(t -> feed.getExchange().name().equalsIgnoreCase(t.getExchange()))
                .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
                .distinct()
                .toList();
            if (!pairs.isEmpty()) {
                feed.subscribe(pairs);
            }
        }
    }
}
