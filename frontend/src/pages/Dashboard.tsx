import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert, Box, Button, Chip, CircularProgress, Container, Dialog, DialogContent, DialogTitle,
  Grid, IconButton, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { ValueType } from 'recharts/types/component/DefaultTooltipContent';
import { useDashboardSocket } from '../hooks/useDashboardSocket';
import { getDrawdown, getEquity, getExecution, getSharpe, getWinRate, getTrade, getStatus, startArbitrage, stopArbitrage } from '../api/rest';
import type { AnalyticsData, ExecutionStats, EquityPoint, TradeDetail, LegStatus } from '../types';

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

const LEG_STATUS_COLOR: Record<LegStatus, 'success' | 'error' | 'default'> = {
  FILLED:    'success',
  SIMULATED: 'success',
  FAILED:    'error',
};

interface TradeDetailDialogProps {
  tradeId: number | null;
  onClose: () => void;
}

function TradeDetailDialog({ tradeId, onClose }: TradeDetailDialogProps) {
  const [detail, setDetail] = useState<TradeDetail | null>(null);

  useEffect(() => {
    if (tradeId == null) return;
    setDetail(null);
    getTrade(tradeId).then((res) => setDetail(res.data));
  }, [tradeId]);

  return (
    <Dialog open={tradeId != null} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        Trade #{tradeId} — Legs
        <IconButton size="small" onClick={onClose}><CloseIcon fontSize="small" /></IconButton>
      </DialogTitle>
      <DialogContent>
        {!detail ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress />
          </Box>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>#</TableCell>
                  <TableCell>Pair</TableCell>
                  <TableCell>Direction</TableCell>
                  <TableCell align="right">Price</TableCell>
                  <TableCell align="right">Volume</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Order ID</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {detail.legs.map((leg) => (
                  <TableRow key={leg.legIndex}>
                    <TableCell>{leg.legIndex}</TableCell>
                    <TableCell>{leg.pair}</TableCell>
                    <TableCell>
                      <Chip
                        label={leg.direction}
                        color={leg.direction === 'BUY' ? 'success' : 'warning'}
                        size="small"
                        variant="outlined"
                      />
                    </TableCell>
                    <TableCell align="right">{leg.price.toFixed(5)}</TableCell>
                    <TableCell align="right">{leg.volume.toFixed(2)}</TableCell>
                    <TableCell>
                      <Chip label={leg.status} color={LEG_STATUS_COLOR[leg.status]} size="small" />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'text.secondary' }}>
                      {leg.orderId ?? '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function Dashboard() {
  const live = useDashboardSocket();
  const [analytics, setAnalytics] = useState<AnalyticsData>({ drawdown: 0, sharpe: 0, winRate: 0 });
  const [execution, setExecution] = useState<ExecutionStats>({ avgLatency: 0, maxLatency: 0, fillRate: 0 });
  const [equity, setEquity] = useState<EquityPoint[]>([]);
  const [selectedTradeId, setSelectedTradeId] = useState<number | null>(null);
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
  const trades    = live?.recentTrades ?? [];

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="h4">Triangular Arbitrage Dashboard</Typography>
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
            <Box sx={{ display: 'flex', gap: 4 }}>
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
            <Box sx={{ display: 'flex', gap: 4 }}>
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

      {/* Row 4: Recent trades */}
      <Paper>
        <Typography variant="subtitle1" sx={{ p: 2, pb: 1 }}>
          Recent Trades{' '}
          <Typography component="span" variant="caption" color="text.secondary">
            — click a row for leg details
          </Typography>
        </Typography>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Time</TableCell>
                <TableCell>Dir</TableCell>
                <TableCell align="right">Spread</TableCell>
                <TableCell align="right">PnL</TableCell>
                <TableCell align="right">Latency</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {trades.map((t) => (
                <TableRow
                  key={t.id}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => setSelectedTradeId(t.id)}
                >
                  <TableCell>{t.time.substring(0, 19).replace('T', ' ')}</TableCell>
                  <TableCell>{t.direction}</TableCell>
                  <TableCell align="right">{t.spread.toFixed(5)}</TableCell>
                  <TableCell align="right" sx={{ color: t.pnl >= 0 ? 'success.main' : 'error.main' }}>
                    ${t.pnl.toFixed(2)}
                  </TableCell>
                  <TableCell align="right">{t.latencyMs.toFixed(0)} ms</TableCell>
                  <TableCell>
                    <Chip
                      label={t.status}
                      color={t.status === 'FILLED' ? 'success' : t.status === 'SIMULATION' ? 'info' : 'default'}
                      size="small"
                    />
                  </TableCell>
                </TableRow>
              ))}
              {trades.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No trades yet
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      <TradeDetailDialog
        tradeId={selectedTradeId}
        onClose={() => setSelectedTradeId(null)}
      />
    </Container>
  );
}
