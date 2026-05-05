import axios from 'axios';
import type { AxiosResponse } from 'axios';
import type {
  AppUser, ArbitrageStats, AuthUser, BalanceEntry, EquityPoint, ExecutionStats,
  ManualLeg, OpenOrder, Setting, Trade, TradeDetail, TriangleConfig,
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
interface SharpeResponse    { sharpe: number }
interface ArbitrageStatus   { running: boolean; stats: ArbitrageStats }
interface BrokerHealth      { connected: boolean }

export const login   = (username: string, password: string): Promise<AxiosResponse<AuthUser>> =>
  client.post('/auth/login', { username, password });
export const logout  = (): Promise<AxiosResponse<void>>      => client.post('/auth/logout');
export const getMe   = (): Promise<AxiosResponse<AuthUser>>  => client.get('/auth/me');

export const startArbitrage        = (): Promise<AxiosResponse<void>>               => client.post('/arbitrage/start');
export const stopArbitrage         = (): Promise<AxiosResponse<void>>               => client.post('/arbitrage/stop');
export const getStatus             = (): Promise<AxiosResponse<ArbitrageStatus>>    => client.get('/arbitrage/status');
export const getDailyProfitAndLoss = (): Promise<AxiosResponse<DailyPnlResponse>>  => client.get('/stats/daily-pnl');
export const getDrawdown           = (): Promise<AxiosResponse<DrawdownResponse>>   => client.get('/stats/drawdown');
export const getWinRate            = (): Promise<AxiosResponse<WinRateResponse>>    => client.get('/stats/win-rate');
export const getSharpe             = (): Promise<AxiosResponse<SharpeResponse>>     => client.get('/stats/sharpe');
export const getArbStats           = (): Promise<AxiosResponse<ArbitrageStats>>     => client.get('/stats/arb');
export const getEquity             = (): Promise<AxiosResponse<EquityPoint[]>>      => client.get('/stats/equity');
export const getExecution          = (): Promise<AxiosResponse<ExecutionStats>>     => client.get('/stats/execution');
export const getTrades             = (): Promise<AxiosResponse<Trade[]>>            => client.get('/trades');
export const getTrade              = (id: number): Promise<AxiosResponse<TradeDetail>> => client.get(`/trades/${id}`);
export const getSettings           = (): Promise<AxiosResponse<Setting[]>>          => client.get('/settings');
export const putSettings           = (data: Record<string, number>): Promise<AxiosResponse<void>> => client.put('/settings', data);
export const getBrokerHealth       = (): Promise<AxiosResponse<BrokerHealth>>       => client.get('/broker/health');
export const getPositions          = (): Promise<AxiosResponse<BalanceEntry[]>>     => client.get('/positions');
export const getOpenOrders         = (): Promise<AxiosResponse<OpenOrder[]>>        => client.get('/orders/open');

export const manualTrade = (triangleId: number, cycle: 'A' | 'B', legs: ManualLeg[]) =>
  client.post<{ tradeId: number; status: string; pnl: number }>('/arbitrage/manual-trade', { triangleId, cycle, legs });

type TrianglePayload = Omit<TriangleConfig, 'id' | 'hits' | 'totalProfitUsd'>;
export const getTriangles    = (): Promise<AxiosResponse<TriangleConfig[]>>          => client.get('/triangles');
export const createTriangle  = (data: TrianglePayload): Promise<AxiosResponse<TriangleConfig>> => client.post('/triangles', data);
export const updateTriangle  = (id: number, data: TrianglePayload): Promise<AxiosResponse<TriangleConfig>> => client.put(`/triangles/${id}`, data);
export const deleteTriangle  = (id: number): Promise<AxiosResponse<void>>            => client.delete(`/triangles/${id}`);

export const getUsers   = ():                                            Promise<AxiosResponse<AppUser[]>> => client.get('/users');
export const createUser = (username: string, password: string):         Promise<AxiosResponse<AppUser>>   => client.post('/users', { username, password });
export const deleteUser = (id: number):                                  Promise<AxiosResponse<void>>      => client.delete(`/users/${id}`);
