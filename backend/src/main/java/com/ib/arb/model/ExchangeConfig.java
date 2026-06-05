package com.ib.arb.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exchange_configs")
public class ExchangeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String exchange;

    private boolean enabled;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "api_secret")
    private String apiSecret;

    @Column(name = "api_passphrase")
    private String apiPassphrase;

    @Column(name = "ws_url")
    private String wsUrl;

    @Column(name = "order_size_usd")
    private double orderSizeUsd = 100_000;

    @Column(name = "position_limit_usd")
    private double positionLimitUsd = 10_000;

    @Column(name = "max_daily_loss_usd")
    private double maxDailyLossUsd = -1_000;

    @Column(name = "simulation")
    private boolean simulation = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId()                    { return id; }
    public String getExchange()            { return exchange; }
    public boolean isEnabled()             { return enabled; }
    public boolean isSimulation()          { return simulation; }
    public String getApiKey()              { return apiKey; }
    public String getApiSecret()           { return apiSecret; }
    public String getApiPassphrase()       { return apiPassphrase; }
    public String getWsUrl()               { return wsUrl; }
    public double getOrderSizeUsd()        { return orderSizeUsd; }
    public double getPositionLimitUsd()    { return positionLimitUsd; }
    public double getMaxDailyLossUsd()     { return maxDailyLossUsd; }
    public LocalDateTime getCreatedAt()    { return createdAt; }

    public ExchangeConfig setExchange(String exchange)              { this.exchange = exchange;           return this; }
    public ExchangeConfig setEnabled(boolean enabled)               { this.enabled = enabled;             return this; }
    public ExchangeConfig setSimulation(boolean simulation)         { this.simulation = simulation;       return this; }
    public ExchangeConfig setApiKey(String apiKey)                  { this.apiKey = apiKey;               return this; }
    public ExchangeConfig setApiSecret(String apiSecret)            { this.apiSecret = apiSecret;         return this; }
    public ExchangeConfig setApiPassphrase(String apiPassphrase)    { this.apiPassphrase = apiPassphrase; return this; }
    public ExchangeConfig setWsUrl(String wsUrl)                    { this.wsUrl = wsUrl;                 return this; }
    public ExchangeConfig setOrderSizeUsd(double orderSizeUsd)      { this.orderSizeUsd = orderSizeUsd;   return this; }
    public ExchangeConfig setPositionLimitUsd(double v)             { this.positionLimitUsd = v;          return this; }
    public ExchangeConfig setMaxDailyLossUsd(double v)              { this.maxDailyLossUsd = v;           return this; }
    public ExchangeConfig setCreatedAt(LocalDateTime createdAt)     { this.createdAt = createdAt;         return this; }
}
