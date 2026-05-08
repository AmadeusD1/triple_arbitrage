package com.ib.arb.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "missed_opportunities")
public class MissedOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime time;
    private Long triangleId;
    private String exchange;
    private String pair1;
    private String pair2;
    private String pair3;
    private String cycle;
    private double edge;
    private double orderSize;
    private String rejection;
    private String reason;

    public Long getId()           { return id; }
    public LocalDateTime getTime(){ return time; }
    public Long getTriangleId()   { return triangleId; }
    public String getExchange()   { return exchange; }
    public String getPair1()      { return pair1; }
    public String getPair2()      { return pair2; }
    public String getPair3()      { return pair3; }
    public String getCycle()      { return cycle; }
    public double getEdge()       { return edge; }
    public double getOrderSize()  { return orderSize; }
    public String getRejection()  { return rejection; }
    public String getReason()     { return reason; }

    public MissedOpportunity setTime(LocalDateTime time)     { this.time = time;             return this; }
    public MissedOpportunity setTriangleId(Long triangleId)  { this.triangleId = triangleId; return this; }
    public MissedOpportunity setExchange(String exchange)    { this.exchange = exchange;      return this; }
    public MissedOpportunity setPair1(String pair1)          { this.pair1 = pair1;            return this; }
    public MissedOpportunity setPair2(String pair2)          { this.pair2 = pair2;            return this; }
    public MissedOpportunity setPair3(String pair3)          { this.pair3 = pair3;            return this; }
    public MissedOpportunity setCycle(String cycle)          { this.cycle = cycle;            return this; }
    public MissedOpportunity setEdge(double edge)            { this.edge = edge;              return this; }
    public MissedOpportunity setOrderSize(double orderSize)  { this.orderSize = orderSize;    return this; }
    public MissedOpportunity setRejection(String rejection)  { this.rejection = rejection;    return this; }
    public MissedOpportunity setReason(String reason)        { this.reason = reason;          return this; }
}
