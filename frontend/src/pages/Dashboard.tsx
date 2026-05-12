import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert, Box, Button, Chip, Container, Grid, Paper, Typography,
} from '@mui/material';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { ValueType } from 'recharts/types/component/DefaultTooltipContent';
import { useDashboardSocket } from '../hooks/useDashboardSocket';
import { getDrawdown, getEquity, getExecution, getSharpe, getWinRate, getStatus, startArbitrage, stopArbitrage } from '../api/rest';
import type { AnalyticsData, ExecutionStats, EquityPoint } from '../types';

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
  const [analytics, setAnalytics] = useState<AnalyticsData>({ drawdown: 0, sharpe: 0, winRate: 0 });
  const [execution, setExecution] = useState<ExecutionStats>({ avgLatency: 0, maxLatency: 0, fillRate: 0 });
  const [equity, setEquity] = useState<EquityPoint[]>([]);
  const [running, setRunning] = useState(false);

  useEffect(() => {
    const load = async () => {
      const [dd, sh, wr, ex, eq, status] = await Promise.all([
        getDrawdown(), getSharpe(), getWinRate(), getExecution(), getEquity(), getStatus(),
      ]);
      setAnalytics({ drawdown: dd.data.drawdown ?? 0, sharpe: sh.data.sharpe ?? 0, winRate: wr.data.winRate ?? 0 });
      setExecution({ avgLatency: ex.data.avgLatency ?? 0, maxLatency: ex.data.maxLatency ?? 0, fillRate: ex.data.fillRate ?? 0 });
      setEquity(eq.data ?? []);
      setRunning(status.data.running);
    };
    void load();
    const t = setInterval(() => void load(), 10_000);
    return () => clearInterval(t);
  }, []);

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
        <Button
          variant="contained"
          color={running ? 'error' : 'success'}
          onClick={() => void handleToggle()}
        >
          {running ? 'Stop' : 'Start'}
        </Button>
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
              ${pnl.toFixed(2)}
            </Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 2 }}>
          <StatCard label="Win Rate">
            <Typography variant="h5">{analytics.winRate.toFixed(1)}%</Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 2 }}>
          <StatCard label="Sharpe">
            <Typography variant="h5">{analytics.sharpe.toFixed(2)}</Typography>
          </StatCard>
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <StatCard label="Max Drawdown">
            <Typography variant="h5" color="error">${analytics.drawdown.toFixed(2)}</Typography>
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
                ['Avg Edge', arb.avgEdge.toFixed(5)],
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
                ['Avg Latency', `${execution.avgLatency.toFixed(0)} ms`],
                ['Max Latency', `${execution.maxLatency.toFixed(0)} ms`],
                ['Fill Rate',   `${execution.fillRate.toFixed(1)}%`],
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
        <Typography variant="subtitle1" gutterBottom>Equity Curve</Typography>
        <ResponsiveContainer width="100%" height={200}>
          <LineChart data={equity}>
            <XAxis dataKey="time" hide />
            <YAxis tickFormatter={(v: number) => `$${v.toFixed(0)}`} width={70} />
            <Tooltip
              formatter={(v: ValueType | undefined) =>
                typeof v === 'number' ? [`$${v.toFixed(2)}`, 'Equity'] : ['', 'Equity']
              }
            />
            <Line type="monotone" dataKey="equity" dot={false} stroke="#1976d2" strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </Paper>

    </Container>
  );
}
