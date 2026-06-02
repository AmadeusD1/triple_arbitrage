package com.ib.arb.broker;

import com.ib.arb.repository.ExchangeConfigRepository;
import com.ib.arb.repository.SettingRepository;
import static com.ib.arb.common.Constants.Simulation.SIMULATION_MODE_KEY;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for exchange order clients.
 * Provides simulation-mode check (global {@code simulation_mode} setting) and
 * open-order counting, shared by all exchange implementations.
 */
public abstract class AbstractOrderClient implements OrderClient {

    protected final SettingRepository settings;
    protected final ExchangeConfigRepository configRepo;
    protected final AtomicInteger openOrders = new AtomicInteger(0);

    protected AbstractOrderClient(SettingRepository settings, ExchangeConfigRepository configRepo) {
        this.settings = settings;
        this.configRepo = configRepo;
    }

    @Override
    public boolean isSimulation() {
        return settings.findById(SIMULATION_MODE_KEY)
            .map(s -> s.getValue() == 1.0)
            .orElse(true);
    }

    @Override
    public int openOrderCount() {
        return openOrders.get();
    }

    /** Returns the API key from exchange_configs, blank string if not configured. */
    protected String apiKey() {
        return configRepo.findByExchange(getExchange().name())
            .map(c -> c.getApiKey() != null ? c.getApiKey() : "")
            .orElse("");
    }

    /** Returns the API secret from exchange_configs, blank string if not configured. */
    protected String apiSecret() {
        return configRepo.findByExchange(getExchange().name())
            .map(c -> c.getApiSecret() != null ? c.getApiSecret() : "")
            .orElse("");
    }

    /** Returns the API passphrase from exchange_configs (used by Coinbase and KuCoin). */
    protected String apiPassphrase() {
        return configRepo.findByExchange(getExchange().name())
            .map(c -> c.getApiPassphrase() != null ? c.getApiPassphrase() : "")
            .orElse("");
    }

    @Override
    public boolean isConnected() {
        return isSimulation() || (!apiKey().isBlank() && !apiSecret().isBlank());
    }
}
