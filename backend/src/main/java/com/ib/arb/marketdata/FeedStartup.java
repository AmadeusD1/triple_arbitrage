package com.ib.arb.marketdata;

import com.ib.arb.position.PositionService;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.TriangleConfigRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FeedStartup implements ApplicationRunner {

    private final List<OrderBookFeed> feeds;
    private final TriangleConfigRepository triangleRepo;
    private final ExchangeConfigRepository exchangeConfigRepo;
    private final PositionService positionService;

    public FeedStartup(List<OrderBookFeed> feeds, TriangleConfigRepository triangleRepo,
                       ExchangeConfigRepository exchangeConfigRepo,
                       PositionService positionService) {
        this.feeds              = feeds;
        this.triangleRepo       = triangleRepo;
        this.exchangeConfigRepo = exchangeConfigRepo;
        this.positionService    = positionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var feed : feeds) {
            var exchangeName = feed.getExchange().name();
            var config = exchangeConfigRepo.findByExchange(exchangeName).orElse(null);
            if (config == null || !config.isEnabled()) continue;

            var pairs = triangleRepo.findAll().stream()
                .filter(t -> exchangeName.equalsIgnoreCase(t.getExchange()))
                .flatMap(t -> List.of(t.getPair1(), t.getPair2(), t.getPair3()).stream())
                .distinct()
                .toList();
            if (!pairs.isEmpty()) {
                feed.subscribe(pairs);
            }
        }
        positionService.startupRefresh();
    }
}
