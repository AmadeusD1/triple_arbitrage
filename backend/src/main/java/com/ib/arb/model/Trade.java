package com.ib.arb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime time;
    private String direction;
    private double spread;
    private double pnl;
    private String status;
    private double latencyMs;
    private double orderSize;
    private double expectedPnl;
    private Double realProfit;
    private Double realProfitPercent;

    @OneToMany(mappedBy = "trade", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TradeLeg> legs = new ArrayList<>();

    public Long getId() { return id; }
    public LocalDateTime getTime() { return time; }
    public String getDirection() { return direction; }
    public double getSpread() { return spread; }
    public double getPnl() { return pnl; }
    public String getStatus() { return status; }
    public double getLatencyMs() { return latencyMs; }
    public double getOrderSize() { return orderSize; }
    public double getExpectedPnl() { return expectedPnl; }
    public Double getRealProfit() { return realProfit; }
    public Double getRealProfitPercent() { return realProfitPercent; }
    @JsonIgnore public List<TradeLeg> getLegs() { return legs; }

    public Trade setTime(LocalDateTime time)                   { this.time = time;                         return this; }
    public Trade setDirection(String direction)                { this.direction = direction;               return this; }
    public Trade setSpread(double spread)                      { this.spread = spread;                     return this; }
    public Trade setPnl(double pnl)                           { this.pnl = pnl;                           return this; }
    public Trade setStatus(String status)                      { this.status = status;                     return this; }
    public Trade setLatencyMs(double latencyMs)                { this.latencyMs = latencyMs;               return this; }
    public Trade setOrderSize(double orderSize)                { this.orderSize = orderSize;               return this; }
    public Trade setExpectedPnl(double expectedPnl)            { this.expectedPnl = expectedPnl;           return this; }
    public Trade setRealProfit(Double realProfit)              { this.realProfit = realProfit;             return this; }
    public Trade setRealProfitPercent(Double realProfitPercent){ this.realProfitPercent = realProfitPercent; return this; }

    public Trade addLeg(TradeLeg leg) {
        legs.add(leg);
        leg.setTrade(this);
        return this;
    }
}
