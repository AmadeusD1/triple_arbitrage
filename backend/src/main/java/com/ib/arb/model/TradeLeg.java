package com.ib.arb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;


@Entity
@Table(name = "trade_legs")
public class TradeLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    private int legIndex;
    private String pair;
    private String direction;
    private double price;
    private double volume;
    private String status;
    private String orderId;
    private Double quoteRate;

    public Long getId() { return id; }
    public int getLegIndex() { return legIndex; }
    public String getPair() { return pair; }
    public String getDirection() { return direction; }
    public double getPrice() { return price; }
    public double getVolume() { return volume; }
    public String getStatus() { return status; }
    public String getOrderId() { return orderId; }
    public Double getQuoteRate() { return quoteRate; }
    @JsonIgnore public Trade getTrade() { return trade; }

    public TradeLeg setTrade(Trade trade)         { this.trade = trade;         return this; }
    public TradeLeg setLegIndex(int legIndex)      { this.legIndex = legIndex;   return this; }
    public TradeLeg setPair(String pair)           { this.pair = pair;           return this; }
    public TradeLeg setDirection(String direction) { this.direction = direction; return this; }
    public TradeLeg setPrice(double price)         { this.price = price;         return this; }
    public TradeLeg setVolume(double volume)       { this.volume = volume;       return this; }
    public TradeLeg setStatus(String status)       { this.status = status;       return this; }
    public TradeLeg setOrderId(String orderId)     { this.orderId = orderId;     return this; }
    public TradeLeg setQuoteRate(Double quoteRate) { this.quoteRate = quoteRate; return this; }
}
