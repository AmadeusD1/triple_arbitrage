package com.ib.arb.scheduler;

import com.ib.arb.execution.AutoTrader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ArbitrageScheduler {

    private final AutoTrader autoTrader;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ArbitrageScheduler(AutoTrader autoTrader) {
        this.autoTrader = autoTrader;
    }

    @Scheduled(fixedDelayString = "${arb.scan-interval-ms}")
    public void cycle() {
        if (!running.get()) {
            autoTrader.broadcast();  // push prices even when trading is paused
            return;
        }
        autoTrader.attemptArbitrage();
    }

    public void start() { running.set(true); }
    public void stop()  { running.set(false); }
    public boolean isRunning() { return running.get(); }
}
