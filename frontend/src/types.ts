export type CycleDirection = 'BBS' | 'BSS' | 'BSB' | 'SBS';
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
  exchange: string;
  direction: CycleDirection;
  spread: number;
  pnl: number;
  status: TradeStatus;
  latencyMs: number;
  orderSize: number;
  expectedPnl: number;
  realProfit: number | null;
  realProfitPercent: number | null;
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

export interface MissedOpportunity {
  id: number;
  time: string;
  triangleId: number;
  exchange: string;
  pair1: string;
  pair2: string;
  pair3: string;
  cycle: CycleDirection;
  edge: number;
  orderSize: number;
  rejection: string;
  reason: string | null;
  expectedPnl: number;
  leg1Price: number;
  leg1Volume: number;
  leg2Price: number;
  leg2Volume: number;
  leg3Price: number;
  leg3Volume: number;
}

export interface DashboardSnapshot {
  dailyProfitAndLoss: number;
  brokerConnected: boolean;
  arbStats: ArbitrageStats;
  recentTrades: Trade[];
  prices: PriceSnapshot[];
  tradeInProgress: boolean;
  fxRates: Record<string, number>;
  recentMissedOpportunities: MissedOpportunity[];
  exchangeRunning: Record<string, boolean>;
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
  monthlyPnl: number;
  winRate: number;
}

export interface AuthUser {
  username: string;
  role: string;
}

export interface AppUser {
  id: number;
  username: string;
  role: string;
}

export interface OrderLeg {
  legIndex: number;
  pair: string;
  direction: string;
  price: number;
  quantity: number;
}

export interface BalanceEntry {
  exchange: string;
  currency: string;
  assetKey: string;
  amount: number;
}

export interface ExchangeConfig {
  id: number;
  exchange: string;
  enabled: boolean;
  simulation: boolean;
  apiKey: string | null;
  apiSecret: string | null;
  apiPassphrase: string | null;
  wsUrl: string | null;
  orderSizeUsd: number;
  positionLimitUsd: number;
  maxDailyLossUsd: number;
  createdAt: string;
}

export interface OpenOrder {
  txid: string;
  pair: string;
  side: string;
  orderType: string;
  price: number;
  volume: number;
  volumeFilled: number;
  openTime: number;
  status: string;
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
  cycle: string;
}
