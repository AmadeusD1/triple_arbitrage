export type CycleDirection = 'A' | 'B';
export type LegDirection = 'BUY' | 'SELL';
export type TradeStatus = 'FILLED' | 'CANCELLED' | 'SIMULATION';
export type LegStatus = 'FILLED' | 'FAILED' | 'SIMULATED';

export interface TradeLeg {
  id: number;
  legIndex: number;
  pair: string;
  direction: LegDirection;
  price: number;
  volume: number;
  status: LegStatus;
  orderId: string | null;
}

export interface Trade {
  id: number;
  time: string;
  direction: CycleDirection;
  spread: number;
  pnl: number;
  status: TradeStatus;
  latencyMs: number;
}

export interface TradeDetail extends Trade {
  legs: TradeLeg[];
}

export interface ArbitrageStats {
  detected: number;
  executed: number;
  missed: number;
  avgEdge: number;
}

export interface PriceSnapshot {
  exchange: string;
  pair: string;
  bid: number;
  ask: number;
}

export interface DashboardSnapshot {
  dailyProfitAndLoss: number;
  brokerConnected: boolean;
  arbStats: ArbitrageStats;
  recentTrades: Trade[];
  prices: PriceSnapshot[];
  tradeInProgress: boolean;
}

export interface EquityPoint {
  time: string;
  equity: number;
}

export interface Setting {
  key: string;
  value: number;
}

export interface ExecutionStats {
  avgLatency: number;
  maxLatency: number;
  fillRate: number;
}

export interface AnalyticsData {
  drawdown: number;
  sharpe: number;
  winRate: number;
}

export interface AuthUser {
  username: string;
}

export interface ManualLeg {
  legIndex: number;
  pair: string;
  direction: string;
  price: number;
  volume: number;
}

export type TriangleStatus = 'ACTIVE' | 'INACTIVE';

export interface TriangleConfig {
  id: number;
  exchange: string;
  pair1: string;
  pair2: string;
  pair3: string;
  minProfitUsd: number;
  minProfitPercent: number;
  status: TriangleStatus;
  hits: number;
  totalProfitUsd: number;
}
