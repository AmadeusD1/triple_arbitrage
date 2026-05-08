package com.ib.arb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ib.arb.analytics.AnalyticsService;
import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.execution.AutoTrader;
import com.ib.arb.marketdata.CurrencyRateFeed;
import com.ib.arb.repository.MissedOpportunityRepository;
import com.ib.arb.repository.TradeRepository;
import com.ib.arb.scanner.ArbitrageEngine;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper;
    private volatile String lastPayload = null;

    private final AnalyticsService analytics;
    private final KrakenOrderClient broker;
    private final TradeRepository tradeRepo;
    private final ArbitrageEngine arbitrageEngine;
    private final CurrencyRateFeed currencyRateFeed;
    private final AutoTrader autoTrader;
    private final MissedOpportunityRepository missedOpportunityRepo;

    public DashboardWebSocketHandler(AnalyticsService analytics,
                                     KrakenOrderClient broker,
                                     TradeRepository tradeRepo,
                                     ArbitrageEngine arbitrageEngine,
                                     CurrencyRateFeed currencyRateFeed,
                                     AutoTrader autoTrader,
                                     MissedOpportunityRepository missedOpportunityRepo,
                                     ObjectMapper mapper) {
        this.analytics = analytics;
        this.broker = broker;
        this.tradeRepo = tradeRepo;
        this.arbitrageEngine = arbitrageEngine;
        this.currencyRateFeed = currencyRateFeed;
        this.autoTrader = autoTrader;
        this.missedOpportunityRepo = missedOpportunityRepo;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        if (lastPayload != null) {
            try { session.sendMessage(new TextMessage(lastPayload)); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast() {
        send(new DashboardSnapshot(
            analytics.dailyProfitAndLoss(),
            broker.isConnected(),
            autoTrader.getStats(),
            tradeRepo.findTop20ByOrderByTimeDesc(),
            arbitrageEngine.currentSnapshots(),
            autoTrader.isExecuting(),
            currencyRateFeed.getAllRates(),
            missedOpportunityRepo.findTop1000ByOrderByTimeDesc()
        ));
    }

    private void send(Object payload) {
        try {
            lastPayload = mapper.writeValueAsString(payload);
            var message = new TextMessage(lastPayload);
            for (var session : sessions) {
                if (session.isOpen()) session.sendMessage(message);
            }
        } catch (Exception ignored) {}
    }
}
