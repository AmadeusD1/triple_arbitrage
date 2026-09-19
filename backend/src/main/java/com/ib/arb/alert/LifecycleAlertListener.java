package com.ib.arb.alert;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emails on app start/stop. ContextClosedEvent (not @PreDestroy) deliberately: it fires
 * before any bean, including AlertService's own JavaMailSender, starts getting torn down, so
 * the alert is guaranteed a live mail sender to use. Only covers graceful shutdown
 * (systemctl stop/restart, i.e. SIGTERM) - a hard crash or kill -9 gives no code a chance to
 * run at all.
 */
@Component
public class LifecycleAlertListener {

    private final AlertService alertService;

    public LifecycleAlertListener(AlertService alertService) {
        this.alertService = alertService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        alertService.applicationStarted();
    }

    @EventListener(ContextClosedEvent.class)
    public void onStop() {
        alertService.applicationStopping();
    }
}
