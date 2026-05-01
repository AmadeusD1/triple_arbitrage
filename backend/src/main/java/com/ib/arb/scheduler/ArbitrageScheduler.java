package com.ib.arb.scheduler;

import com.ib.arb.execution.AutoTrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ArbitrageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArbitrageScheduler.class);

    private final AutoTrader autoTrader;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ArbitrageScheduler(AutoTrader autoTrader) {
        this.autoTrader = autoTrader;
    }

    @Scheduled(fixedDelayString = "${arb.scan-interval-ms}")
    public void cycle() {
        if (!running.get()) {
            autoTrader.broadcast(); // push prices even when trading is paused
            return;
        }
        autoTrader.attemptArbitrage();
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
