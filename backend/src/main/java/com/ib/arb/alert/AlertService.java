package com.ib.arb.alert;

import com.ib.arb.scanner.Signal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class AlertService {

    private final JavaMailSender mailSender;

    @Value("${alert.email-from:}")
    private String emailFrom;

    @Value("${alert.email-to:}")
    private String emailTo;

    public AlertService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void tradeFilled(Signal signal, double pnl) {
        var msg = "Executed cycle %s on %s | PnL: %.2f".formatted(
            signal.cycle(), signal.exchange(), pnl);
        send("Triangular Arbitrage Alert", msg);
    }

    private void send(String subject, String body) {
        if (emailTo == null || emailTo.isBlank()) return;
        try {
            var message = new SimpleMailMessage();
            message.setFrom(emailFrom);
            message.setTo(emailTo);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            // alert delivery failure must never crash the trading loop
        }
    }
}
