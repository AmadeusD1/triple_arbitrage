package com.ib.arb.common;

public final class Constants {

    public static final class Direction {
        public static final String BUY  = "BUY";
        public static final String SELL = "SELL";
        private Direction() {}
    }

    public static final class TradeStatus {
        public static final String FILLED     = "FILLED";
        public static final String CANCELLED  = "CANCELLED";
        public static final String SIMULATION = "SIMULATION";
        private TradeStatus() {}
    }

    public static final class LegStatus {
        public static final String FILLED    = "FILLED";
        public static final String FAILED    = "FAILED";
        public static final String SIMULATED = "SIMULATED";
        private LegStatus() {}
    }

    public static final class TriangleStatus {
        public static final String ACTIVE = "ACTIVE";
        private TriangleStatus() {}
    }

    public static final class Simulation {
        public static final String SIMULATION_MODE_KEY = "simulation_mode";
        public static final double SIMULATION_BALANCE  = 10000.0;
        private Simulation() {}
    }

    public static final class RejectionStatus {
        public static final String REJECTED_BALANCE = "REJECTED_BALANCE";
        public static final String REJECTED_RISK    = "REJECTED_RISK";
        public static final String REJECTED_PROFIT  = "REJECTED_PROFIT";
        private RejectionStatus() {}
    }

    private Constants() {}
}
