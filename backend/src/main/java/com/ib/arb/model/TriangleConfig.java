package com.ib.arb.model;

import jakarta.persistence.*;

@Entity
@Table(name = "triangles")
public class TriangleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String exchange;
    private String pair1;
    private String pair2;
    private String pair3;
    private double minProfitUsd;
    private double minProfitPercent;
    private String status;
    private long hits;
    private double totalProfitUsd;

    public Long getId() { return id; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getPair1() { return pair1; }
    public void setPair1(String pair1) { this.pair1 = pair1; }

    public String getPair2() { return pair2; }
    public void setPair2(String pair2) { this.pair2 = pair2; }

    public String getPair3() { return pair3; }
    public void setPair3(String pair3) { this.pair3 = pair3; }

    public double getMinProfitUsd() { return minProfitUsd; }
    public void setMinProfitUsd(double minProfitUsd) { this.minProfitUsd = minProfitUsd; }

    public double getMinProfitPercent() { return minProfitPercent; }
    public void setMinProfitPercent(double minProfitPercent) { this.minProfitPercent = minProfitPercent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getHits() { return hits; }
    public void setHits(long hits) { this.hits = hits; }

    public double getTotalProfitUsd() { return totalProfitUsd; }
    public void setTotalProfitUsd(double totalProfitUsd) { this.totalProfitUsd = totalProfitUsd; }
}
