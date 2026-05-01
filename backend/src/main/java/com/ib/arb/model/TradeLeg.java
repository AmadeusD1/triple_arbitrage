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

    public Long getId() { return id; }
    @JsonIgnore public Trade getTrade() { return trade; }
    public void setTrade(Trade trade) { this.trade = trade; }
    public int getLegIndex() { return legIndex; }
    public void setLegIndex(int legIndex) { this.legIndex = legIndex; }
    public String getPair() { return pair; }
    public void setPair(String pair) { this.pair = pair; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public double getVolume() { return volume; }
    public void setVolume(double volume) { this.volume = volume; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
}
