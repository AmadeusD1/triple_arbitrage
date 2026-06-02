package com.ib.arb.config;

import com.ib.arb.model.ExchangeConfig;
import com.ib.arb.model.User;
import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
import com.ib.arb.repository.UserRepository;
import com.ib.arb.scheduler.ArbitrageScheduler;
import static com.ib.arb.common.Constants.Simulation.SIMULATION_MODE_KEY;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin}")
    private String adminPassword;

    @Value("${kraken.api-key:}")
    private String krakenApiKey;

    @Value("${kraken.api-secret:}")
    private String krakenApiSecret;

    @Value("${kraken.ws-url:wss://ws.kraken.com/v2}")
    private String krakenWsUrl;

    @Value("${arb.order-size-usd:100000}")
    private double defaultOrderSizeUsd;

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final ArbitrageScheduler scheduler;
    private final SettingRepository settingRepo;
    private final ExchangeConfigRepository exchangeConfigRepo;

    public DataInitializer(UserRepository userRepo, PasswordEncoder passwordEncoder,
                           ArbitrageScheduler scheduler, SettingRepository settingRepo,
                           ExchangeConfigRepository exchangeConfigRepo) {
        this.userRepo           = userRepo;
        this.passwordEncoder    = passwordEncoder;
        this.scheduler          = scheduler;
        this.settingRepo        = settingRepo;
        this.exchangeConfigRepo = exchangeConfigRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureAdminUser();
        ensureKrakenConfig();

        var isSimulation = settingRepo.findById(SIMULATION_MODE_KEY)
            .map(s -> s.getValue() == 1.0).orElse(true);
        if (isSimulation) {
            scheduler.start();
            log.info("Simulation mode active — scanner auto-started");
        } else {
            log.info("Live mode — scanner requires manual start via /api/arbitrage/start");
        }
    }

    private void ensureAdminUser() {
        var existing = userRepo.findByUsername(adminUsername);
        if (existing.isEmpty()) {
            var user = new User();
            user.setUsername(adminUsername);
            user.setPassword(passwordEncoder.encode(adminPassword));
            user.setRole("ADMIN");
            userRepo.save(user);
            log.info("Created admin user '{}'", adminUsername);
        } else {
            var user = existing.get();
            user.setPassword(passwordEncoder.encode(adminPassword));
            userRepo.save(user);
            log.info("Updated password for admin user '{}'", adminUsername);
        }
    }

    private void ensureKrakenConfig() {
        if (exchangeConfigRepo.existsByExchange("KRAKEN")) return;
        var cfg = new ExchangeConfig()
            .setExchange("KRAKEN")
            .setEnabled(true)
            .setApiKey(krakenApiKey)
            .setApiSecret(krakenApiSecret)
            .setWsUrl(krakenWsUrl)
            .setOrderSizeUsd(defaultOrderSizeUsd)
            .setPositionLimitUsd(10_000)
            .setMaxDailyLossUsd(-1_000)
            .setCreatedAt(LocalDateTime.now());
        exchangeConfigRepo.save(cfg);
        log.info("Seeded KRAKEN exchange config");
    }
}
