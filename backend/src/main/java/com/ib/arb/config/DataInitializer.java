package com.ib.arb.config;

import com.ib.arb.broker.KrakenOrderClient;
import com.ib.arb.model.User;
import com.ib.arb.repository.UserRepository;
import com.ib.arb.scheduler.ArbitrageScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin}")
    private String adminPassword;

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final ArbitrageScheduler scheduler;
    private final KrakenOrderClient broker;

    public DataInitializer(UserRepository userRepo, PasswordEncoder passwordEncoder,
                           ArbitrageScheduler scheduler, KrakenOrderClient broker) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.scheduler = scheduler;
        this.broker = broker;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepo.count() == 0) {
            var user = new User();
            user.setUsername(adminUsername);
            user.setPassword(passwordEncoder.encode(adminPassword));
            userRepo.save(user);
            log.info("Created admin user '{}'", adminUsername);
        }

        // Auto-start the scanner in simulation mode so the dashboard is live
        // immediately on startup. In live mode the operator must start manually
        // after confirming feed connectivity and credentials.
        if (broker.isSimulation()) {
            scheduler.start();
            log.info("Simulation mode active — scanner auto-started");
        } else {
            log.info("Live mode — scanner requires manual start via /api/arbitrage/start");
        }
    }
}
