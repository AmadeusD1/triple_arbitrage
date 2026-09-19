package com.ib.arb.alert;

import com.ib.arb.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final JavaMailSender mailSender;

    @Value("${alert.email-from:}")
    private String emailFrom;

    @Value("${alert.email-to:}")
    private String emailTo;

    public AlertService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Only called for real (non-simulation) fills - see AutoTrader.executeArbitrage. */
    public void tradeFilled(Trade trade) {
        var sb = new StringBuilder();
        sb.append("Exchange: %s\n".formatted(trade.getExchange()));
        sb.append("Cycle: %s\n".formatted(trade.getDirection()));
        sb.append("PnL: $%.2f (%.4f%%)\n".formatted(trade.getPnl(), trade.getProfitPercent()));
        sb.append("Order Size: $%.2f\n".formatted(trade.getOrderSize()));
        sb.append("Latency: %.0f ms\n".formatted(trade.getLatencyMs()));
        sb.append("Legs:\n");
        for (var leg : trade.getLegs()) {
            sb.append("  #%d %-4s %-10s price=%s qty=%s status=%s orderId=%s\n".formatted(
                leg.getLegIndex(), leg.getDirection(), leg.getPair(),
                leg.getPrice(), leg.getVolume(), leg.getStatus(),
                leg.getOrderId() != null ? leg.getOrderId() : "-"));
        }
        send("Triangular Arbitrage — Trade Filled", sb.toString());
    }

    /**
     * One or more triangles trading {@code pair} on {@code exchangeName} were just disabled
     * because crypto-aggregator saw an absurd/outlier price for that pair on that exchange -
     * see InternalController. The exchange itself and any triangles NOT trading this pair are
     * unaffected. Stays disabled until a human reviews and re-enables it in the Triangles
     * page - this alert is the notification for that, until an email/SMS/WhatsApp provider is
     * configured (see alert.email-* properties) it's log-only.
     */
    public void trianglesDisabledForOutlier(String exchangeName, String pair, String reason, int count) {
        var msg = "%d triangle(s) on %s trading %s DISABLED due to an absurd price from its data feed: %s"
            .formatted(count, exchangeName, pair, reason);
        send("Triangular Arbitrage — Triangles Disabled", msg);
    }

    /**
     * Trading on {@code exchangeName} was just halted because a live order leg was rejected
     * by the exchange (insufficient funds, precision, min order size, etc.) - a rejected leg
     * on a real triangular order risks leaving the other leg(s) unhedged. Stays disabled
     * until a human reviews it and re-enables it in Exchange Settings.
     */
    public void exchangeDisabledForRejectedOrder(String exchangeName, String reason) {
        var msg = "Trading HALTED on %s — a live order leg was rejected: %s".formatted(exchangeName, reason);
        send("Triangular Arbitrage — Exchange Disabled", msg);
    }

    public void applicationStarted() {
        send("Triangular Arbitrage — Started", "The ib application has started.");
    }

    /** Fired on graceful shutdown (systemctl stop/restart) - not on a hard crash or kill -9,
     *  since there's no opportunity to run any code in those cases. */
    public void applicationStopping() {
        send("Triangular Arbitrage — Stopping", "The ib application is shutting down.");
    }

    public void loginSucceeded(String username, String ip) {
        send("Triangular Arbitrage — Login", "User '%s' logged in from %s".formatted(username, ip));
    }

    public void loginFailed(String username, String ip) {
        send("Triangular Arbitrage — FAILED Login Attempt",
            "Failed login attempt for username '%s' from %s".formatted(username, ip));
    }

    private void send(String subject, String body) {
        if (mailSender == null || emailTo == null || emailTo.isBlank()) return;
        try {
            var message = new SimpleMailMessage();
            message.setFrom(emailFrom);
            message.setTo(emailTo);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Alert delivery failed: {}", e.getMessage());
        }
    }
}
