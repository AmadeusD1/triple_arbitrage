package com.ib.arb.model;

import jakarta.persistence.*;

@Entity
@Table(name = "settings")
public class Setting {

    @Id
    private String key;
    private double value;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
}
