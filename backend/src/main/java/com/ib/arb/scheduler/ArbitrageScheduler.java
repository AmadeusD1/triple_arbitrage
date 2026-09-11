package com.ib.arb.scheduler;

import com.ib.arb.config.DashboardWebSocketHandler;
import com.ib.arb.engine.ExchangeManager;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owns the broadcast heartbeat and delegates all scan lifecycle to {@link ExchangeManager}.
 * The global Start/Stop (Dashboard) calls {@link #start()}/{@link #stop()}.
 */
@Component
public class ArbitrageScheduler {

    private final ExchangeManager exchangeManager;
    private final DashboardWebSocketHandler wsHandler;

    public ArbitrageScheduler(ExchangeManager exchangeManager, DashboardWebSocketHandler wsHandler) {
        this.exchangeManager = exchangeManager;
        this.wsHandler       = wsHandler;
    }

    @PostConstruct
    public void wireRealTimeBroadcast() {
        exchangeManager.setFeedUpdateCallback(wsHandler::scheduleBroadcast);
    }

    /** Heartbeat fallback — keeps the dashboard alive even when no prices change. */
    @Scheduled(fixedDelayString = "${arb.broadcast-interval-ms:5000}")
    public void broadcastCycle() {
        wsHandler.broadcast();
    }

    public void start()          { exchangeManager.startAll(); }
    public void stop()           { exchangeManager.stopAll(); }
    public boolean isRunning()   { return exchangeManager.isGloballyRunning(); }
}
