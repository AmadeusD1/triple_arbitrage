import { useEffect, useState } from 'react';
import { useSessionState } from '../hooks/useSessionState';
import type { ReactNode } from 'react';
import {
  Alert, Box, Button, Chip, Container, FormControl, Grid, MenuItem,
  Paper, Select, Tooltip as MuiTooltip, Typography,
} from '@mui/material';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { TooltipProps } from 'recharts';

import { useDashboardSocket } from '../hooks/useDashboardSocket';
import { getDrawdown, getEquity, getExecution, getMonthlyPnl, getWinRate, getStatus, startArbitrage, stopArbitrage } from '../api/rest';
import type { AnalyticsData, ExecutionStats, EquityPoint } from '../types';

type EquityStatusFilter = 'ALL' | 'FILLED' | 'SIMULATION';

function n(v: unknown, decimals: number): string {
  const x = Number(v);
  return (isFinite(x) ? x : 0).toFixed(decimals);
}

const _dtFmt = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/Chicago',
  month: '2-digit', day: '2-digit', year: '2-digit',
  hour: '2-digit', minute: '2-digit', hour12: false,
});

function fmtDateTime(iso: string): string {
  const d = new Date(iso.endsWith('Z') ? iso : iso + 'Z');
  return _dtFmt.format(d);
}

function EquityTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const entry  = payload[0] as any;
  const raw    = entry.value;
  const equity = typeof raw === 'number' && isFinite(raw) ? raw : 0;
  const time: string   = entry.payload?.time ?? '';
  return (
    <Paper sx={{ p: 1, minWidth: 110 }}>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>${n(equity, 2)}</Typography>
      <Typography variant="caption" color="text.secondary">{fmtDateTime(time)}</Typography>
    </Paper>
  );
}

interface StatCardProps {
  label: string;
  children: ReactNode;
}

function StatCard({ label, children }: StatCardProps) {
  return (
    <Paper sx={{ p: 2, height: '100%' }}>
      <Typography variant="subtitle2" color="text.secondary">{label}</Typography>
      {children}
    </Paper>
  );
}

export default function Dashboard() {
  const live = useDashboardSocket();
  const [analytics, setAnalytics] = useState<AnalyticsData>({ drawdown: 0, monthlyPnl: 0, winRate: 0 });
  const [execution, setExecution] = useState<ExecutionStats>({ avgLatency: 0, maxLatency: 0, fillRate: 0 });
  const [equity, setEquity] = useState<EquityPoint[]>([]);
  const [equityStatus, setEquityStatus] = useSessionState<EquityStatusFilter>('dashboard:equityStatus', 'ALL');
  const [running, setRunning] = useState(false);

  useEffect(() => {
    const load = async () => {
      const [dd, mp, wr, ex, eq, status] = await Promise.all([
        getDrawdown(), getMonthlyPnl(), getWinRate(), getExecution(), getEquity(equityStatus), getStatus(),
      ]);
      setAnalytics({ drawdown: dd.data.drawdown ?? 0, monthlyPnl: mp.data.monthlyPnl ?? 0, winRate: wr.data.winRate ?? 0 });
      setExecution({ avgLatency: ex.data.avgLatency ?? 0, maxLatency: ex.data.maxLatency ?? 0, fillRate: ex.data.fillRate ?? 0 });
      setEquity(eq.data ?? []);
      setRunning(status.data.running);
    };
    void load();
    const t = setInterval(() => void load(), 10_000);
    return () => clearInterval(t);
  }, [equityStatus]);

  const handleToggle = async () => {
    if (running) {
      await stopArbitrage();
    } else {
      await startArbitrage();
    }
    const s = await getStatus();
    setRunning(s.data.running);
  };

  const pnl       = live?.dailyProfitAndLoss ?? 0;
  const connected = live?.brokerConnected ?? false;
  const arb       = live?.arbStats ?? { detected: 0, executed: 0, missed: 0, avgEdge: 0 };

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{
        display: 'flex',
        alignItems: { xs: 'flex-start', sm: 'center' },
        flexDirection: { xs: 'column', sm: 'row' },
        justifyContent: 'space-between',
        gap: 1,
        mb: 1,
      }}>
        <Typography variant="h4" sx={{ fontSize: { xs: '1.4rem', sm: '2.125rem' } }}>
          Triangular Arbitrage Dashboard
        </Typography>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          {Object.keys(live?.exchangeAlerts ?? {}).length > 0 && (
            <MuiTooltip
              arrow
              title={
                <Box sx={{ whiteSpace: 'pre-line' }}>
                  {Object.entries(live!.exchangeAlerts)
                    .map(([exchange, message]) => `${exchange}: ${message}`)
                    .join('\n\n')}
                </Box>
              }
            >
              <WarningAmberIcon sx={{ color: '#FBC02D', cursor: 'help' }} />
            </MuiTooltip>
          )}
          <Button
            variant="contained"
            color={running ? 'error' : 'success'}
            onClick={() => void handleToggle()}
          >
            {running ? 'Stop' : 'Start'}
          </Button>
        </Box>
      </Box>

      {live?.tradeInProgress && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Trade executing — new cycles paused until complete
        </Alert>
      )}

      {/* Row 1: KPI cards */}
      <Grid container spacing={2} sx={{ mb: 2 }}>
        <Grid size={{ xs: 6, md: 3 }}>
          <StatCard label="Daily PnL">
            <Typography variant="h5" color={pnl >= 0 ? 'primary' : 'error'}>
              ${n(pnl, 2)}
            </Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 2 }}>
          <StatCard label="Win Rate">
            <Typography variant="h5">{n(analytics.winRate, 1)}%</Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 2 }}>
          <StatCard label="Monthly PnL">
            <Typography variant="h5" color={Number(analytics.monthlyPnl) >= 0 ? 'primary' : 'error'}>
              ${n(analytics.monthlyPnl, 2)}
            </Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <StatCard label="Max Drawdown">
            <Typography variant="h5" color="error">${n(analytics.drawdown, 2)}</Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 2 }}>
          <StatCard label="Broker">
            <Box sx={{ mt: 0.5 }}>
              <Chip
                label={connected ? 'Connected' : 'Disconnected'}
                color={connected ? 'success' : 'error'}
                size="small"
              />
            </Box>
          </StatCard>
        </Grid>
      </Grid>

      {/* Row 2: Scanner + Execution */}
      <Grid container spacing={2} sx={{ mb: 2 }}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle1" gutterBottom>Scanner</Typography>
            <Box sx={{ display: 'flex', gap: { xs: 2, sm: 4 }, flexWrap: 'wrap' }}>
              {([
                ['Detected', arb.detected],
                ['Executed', arb.executed],
                ['Missed',   arb.missed],
                ['Avg Edge', n(arb.avgEdge, 5)],
              ] as [string, string | number][]).map(([label, val]) => (
                <Box key={label}>
                  <Typography variant="caption" color="text.secondary">{label}</Typography>
                  <Typography variant="body1">{val}</Typography>
                </Box>
              ))}
            </Box>
          </Paper>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle1" gutterBottom>Execution</Typography>
            <Box sx={{ display: 'flex', gap: { xs: 2, sm: 4 }, flexWrap: 'wrap' }}>
              {([
                ['Avg Latency', `${n(execution.avgLatency, 0)} ms`],
                ['Max Latency', `${n(execution.maxLatency, 0)} ms`],
                ['Fill Rate',   `${n(execution.fillRate, 1)}%`],
              ] as [string, string][]).map(([label, val]) => (
                <Box key={label}>
                  <Typography variant="caption" color="text.secondary">{label}</Typography>
                  <Typography variant="body1">{val}</Typography>
                </Box>
              ))}
            </Box>
          </Paper>
        </Grid>
      </Grid>

      {/* Row 3: Equity curve */}
      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
          <Typography variant="subtitle1">Equity Curve</Typography>
          <FormControl size="small" sx={{ minWidth: 140 }}>
            <Select
              value={equityStatus}
              onChange={(e) => setEquityStatus(e.target.value as EquityStatusFilter)}
            >
              <MenuItem value="ALL">All</MenuItem>
              <MenuItem value="FILLED">Filled</MenuItem>
              <MenuItem value="SIMULATION">Simulation</MenuItem>
            </Select>
          </FormControl>
        </Box>
        {equity.length > 0 && (
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={equity}>
              <XAxis hide />
              <YAxis tickFormatter={(v) => `$${n(v, 0)}`} width={70} />
              <Tooltip content={<EquityTooltip />} />
              <Line type="monotone" dataKey="equity" dot={false} stroke="#1976d2" strokeWidth={2} />
            </LineChart>
          </ResponsiveContainer>
        )}
      </Paper>

    </Container>
  );
}
