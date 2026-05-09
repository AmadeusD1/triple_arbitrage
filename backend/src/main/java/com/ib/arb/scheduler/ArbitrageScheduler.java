package com.ib.arb.scheduler;

import com.ib.arb.config.DashboardWebSocketHandler;
import com.ib.arb.engine.AutoTrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ArbitrageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArbitrageScheduler.class);

    private final AutoTrader autoTrader;
    private final DashboardWebSocketHandler wsHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ArbitrageScheduler(AutoTrader autoTrader, DashboardWebSocketHandler wsHandler) {
        this.autoTrader = autoTrader;
        this.wsHandler = wsHandler;
    }

    @Scheduled(fixedDelayString = "${arb.scan-interval-ms}")
    public void cycle() {
        if (!running.get()) return;
        autoTrader.attemptArbitrage();
    }

    @Scheduled(fixedDelayString = "${arb.broadcast-interval-ms:1000}")
    public void broadcastCycle() {
        wsHandler.broadcast();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Arbitrage scanner started");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Arbitrage scanner stopped");
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
