package com.ib.arb.alert;

import com.ib.arb.scanner.Signal;
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

    public void tradeFilled(Signal signal, double pnl) {
        var msg = "Executed cycle %s on %s | PnL: %.2f".formatted(
            signal.cycle(), signal.exchange(), pnl);
        send("Triangular Arbitrage Alert", msg);
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
