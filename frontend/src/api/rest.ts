import axios from 'axios';
import type { AxiosResponse } from 'axios';
import type {
  AppUser, ArbitrageStats, AuthUser, BalanceEntry, CycleDirection, EquityPoint, ExecutionStats,
  ExchangeConfig, OrderLeg, OpenOrder, Setting, Trade, TradeDetail, TriangleConfig,
} from '../types';

const client = axios.create({ baseURL: '/api' });

// Redirect to /login on 401 (unless already there)
client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && window.location.pathname !== '/login') {
      window.location.href = '/login';
    }
    return Promise.reject(err);
  },
);

interface DailyPnlResponse { dailyProfitAndLoss: number }
interface DrawdownResponse  { drawdown: number }
interface WinRateResponse   { winRate: number }
interface ArbitrageStatus   { running: boolean; stats: ArbitrageStats }
interface BrokerHealth      { connected: boolean }

export const login   = (username: string, password: string): Promise<AxiosResponse<AuthUser>> =>
  client.post('/auth/login', { username, password });
export const logout  = (): Promise<AxiosResponse<void>>      => client.post('/auth/logout');
export const getMe   = (): Promise<AxiosResponse<AuthUser>>  => client.get('/auth/me');

export const startArbitrage        = (): Promise<AxiosResponse<void>>               => client.post('/arbitrage/start');
export const stopArbitrage         = (): Promise<AxiosResponse<void>>               => client.post('/arbitrage/stop');
export const startExchange         = (name: string): Promise<AxiosResponse<void>>   => client.post(`/arbitrage/exchanges/${name}/start`);
export const stopExchange          = (name: string): Promise<AxiosResponse<void>>   => client.post(`/arbitrage/exchanges/${name}/stop`);
export const getStatus             = (): Promise<AxiosResponse<ArbitrageStatus>>    => client.get('/arbitrage/status');
export const getDailyProfitAndLoss = (): Promise<AxiosResponse<DailyPnlResponse>>  => client.get('/stats/daily-pnl');
export const getDrawdown           = (): Promise<AxiosResponse<DrawdownResponse>>   => client.get('/stats/drawdown');
export const getWinRate            = (): Promise<AxiosResponse<WinRateResponse>>    => client.get('/stats/win-rate');
export const getMonthlyPnl         = (): Promise<AxiosResponse<{ monthlyPnl: number }>> => client.get('/stats/monthly-pnl');
export const getArbStats           = (): Promise<AxiosResponse<ArbitrageStats>>     => client.get('/stats/arb');
export const getEquity             = (status = 'ALL'): Promise<AxiosResponse<EquityPoint[]>> => client.get('/stats/equity', { params: { status } });
export const getExecution          = (): Promise<AxiosResponse<ExecutionStats>>     => client.get('/stats/execution');
export const getTrades             = (): Promise<AxiosResponse<Trade[]>>            => client.get('/trades');
export const getTrade              = (id: number): Promise<AxiosResponse<TradeDetail>> => client.get(`/trades/${id}`);
export const deleteSimulationTrades = (): Promise<AxiosResponse<void>>                => client.delete('/trades/simulation');
export const getSettings           = (): Promise<AxiosResponse<Setting[]>>          => client.get('/settings');
export const putSettings           = (data: Record<string, number>): Promise<AxiosResponse<void>> => client.put('/settings', data);
export const getBrokerHealth       = (): Promise<AxiosResponse<BrokerHealth>>       => client.get('/broker/health');
export const getPositions          = (): Promise<AxiosResponse<BalanceEntry[]>>     => client.get('/positions');
export const getOpenOrders         = (): Promise<AxiosResponse<OpenOrder[]>>        => client.get('/orders/open');

export const clearMissedOpportunities = (): Promise<AxiosResponse<void>> =>
  client.delete('/missed-opportunities');

export const manualTrade = (triangleId: number, cycle: CycleDirection, legs: OrderLeg[]) =>
  client.post<{ tradeId: number; status: string; pnl: number }>('/arbitrage/manual-trade', { triangleId, cycle, legs });

type TrianglePayload = Omit<TriangleConfig, 'id' | 'hits' | 'totalProfitUsd'>;
export const getTriangles    = (): Promise<AxiosResponse<TriangleConfig[]>>          => client.get('/triangles');
export const createTriangle  = (data: TrianglePayload): Promise<AxiosResponse<TriangleConfig>> => client.post('/triangles', data);
export const updateTriangle  = (id: number, data: TrianglePayload): Promise<AxiosResponse<TriangleConfig>> => client.put(`/triangles/${id}`, data);
export const deleteTriangle  = (id: number): Promise<AxiosResponse<void>>            => client.delete(`/triangles/${id}`);

type ExchangePayload = Omit<ExchangeConfig, 'id' | 'createdAt'>;
export const getExchangeConfigs    = (): Promise<AxiosResponse<ExchangeConfig[]>>   => client.get('/exchanges');
export const createExchangeConfig  = (data: ExchangePayload): Promise<AxiosResponse<ExchangeConfig>> => client.post('/exchanges', data);
export const updateExchangeConfig  = (id: number, data: ExchangePayload): Promise<AxiosResponse<ExchangeConfig>> => client.put(`/exchanges/${id}`, data);
export const deleteExchangeConfig  = (id: number): Promise<AxiosResponse<void>>    => client.delete(`/exchanges/${id}`);

export const getUsers   = ():                                            Promise<AxiosResponse<AppUser[]>> => client.get('/users');
export const createUser = (username: string, password: string, role: string): Promise<AxiosResponse<AppUser>> => client.post('/users', { username, password, role });
export const updateUserRole = (id: number, role: string):               Promise<AxiosResponse<AppUser>>   => client.patch(`/users/${id}/role`, { role });
export const deleteUser     = (id: number):                              Promise<AxiosResponse<void>>      => client.delete(`/users/${id}`);
