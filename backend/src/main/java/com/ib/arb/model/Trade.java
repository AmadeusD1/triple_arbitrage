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
    private String exchange;
    private Double profitPercent;
    private Integer triangleDisplayOrder;
    private String pair1;
    private String pair2;
    private String pair3;

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
    public String getExchange() { return exchange; }
    public Double getProfitPercent() { return profitPercent; }
    public Integer getTriangleDisplayOrder() { return triangleDisplayOrder; }
    public String getPair1() { return pair1; }
    public String getPair2() { return pair2; }
    public String getPair3() { return pair3; }
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
    public Trade setExchange(String exchange)                  { this.exchange = exchange;                   return this; }
    public Trade setProfitPercent(Double profitPercent)        { this.profitPercent = profitPercent;         return this; }
    public Trade setTriangleDisplayOrder(Integer triangleDisplayOrder) { this.triangleDisplayOrder = triangleDisplayOrder; return this; }
    public Trade setPair1(String pair1) { this.pair1 = pair1; return this; }
    public Trade setPair2(String pair2) { this.pair2 = pair2; return this; }
    public Trade setPair3(String pair3) { this.pair3 = pair3; return this; }

    public Trade addLeg(TradeLeg leg) {
        legs.add(leg);
        leg.setTrade(this);
        return this;
    }
}
