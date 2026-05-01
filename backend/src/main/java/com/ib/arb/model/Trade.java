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

    @OneToMany(mappedBy = "trade", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TradeLeg> legs = new ArrayList<>();

    public Long getId() { return id; }
    public LocalDateTime getTime() { return time; }
    public void setTime(LocalDateTime time) { this.time = time; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public double getSpread() { return spread; }
    public void setSpread(double spread) { this.spread = spread; }
    public double getPnl() { return pnl; }
    public void setPnl(double pnl) { this.pnl = pnl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getLatencyMs() { return latencyMs; }
    public void setLatencyMs(double latencyMs) { this.latencyMs = latencyMs; }
    @JsonIgnore public List<TradeLeg> getLegs() { return legs; }

    public void addLeg(TradeLeg leg) {
        legs.add(leg);
        leg.setTrade(this);
    }
}
