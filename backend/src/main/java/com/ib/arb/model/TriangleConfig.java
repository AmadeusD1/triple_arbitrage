package com.ib.arb.model;

import jakarta.persistence.*;

@Entity
@Table(name = "triangles")
public class TriangleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_order")
    private Integer displayOrder;

    private String exchange;
    private String pair1;
    private String pair2;
    private String pair3;
    private double minProfitUsd;
    private double minProfitPercent;
    private String status;
    private long hits;
    private double totalProfitUsd;
    private String cycle;

    @Column(name = "stale_ms_1")
    private int staleMs1 = 10_000;

    @Column(name = "stale_ms_2")
    private int staleMs2 = 10_000;

    @Column(name = "stale_ms_3")
    private int staleMs3 = 10_000;

    public Long getId() { return id; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

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

    public String getCycle() { return cycle; }
    public void setCycle(String cycle) { this.cycle = cycle; }

    public int getStaleMs1() { return staleMs1; }
    public void setStaleMs1(int staleMs1) { this.staleMs1 = staleMs1; }

    public int getStaleMs2() { return staleMs2; }
    public void setStaleMs2(int staleMs2) { this.staleMs2 = staleMs2; }

    public int getStaleMs3() { return staleMs3; }
    public void setStaleMs3(int staleMs3) { this.staleMs3 = staleMs3; }
}
