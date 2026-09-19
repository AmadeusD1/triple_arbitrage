import { useEffect, useMemo, useState } from 'react';
import { useSessionState } from '../hooks/useSessionState';
import {
  Box, Button, Chip, CircularProgress, Container, Dialog, DialogContent, DialogTitle,
  FormControl, IconButton, InputLabel, MenuItem, Paper, Select, Table, TableBody,
  TableCell, TableContainer, TableHead, TablePagination, TableRow, Typography,
  useTheme, useMediaQuery,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { getTrade, getTrades, deleteSimulationTrades } from '../api/rest';
import type { Trade, TradeDetail, LegStatus } from '../types';

const n = (v: unknown, d: number) => (isFinite(Number(v)) ? Number(v) : 0).toFixed(d);
// Shows the real rounded value (as sent to the exchange) without raw binary floating-point
// noise: fixed to 8 decimals, trailing zeros trimmed.
const full = (v: unknown): string => {
  const num = Number(v);
  if (!isFinite(num)) return '0';
  return num.toFixed(8).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
};

const QUOTE_SUFFIXES = ['USDT', 'USDC', 'BUSD', 'EUR', 'GBP', 'JPY', 'TRY', 'USD', 'BTC', 'ETH'];
function quoteCcy(pair: string): string {
  const norm = pair.replace('/', '').toUpperCase();
  const found = QUOTE_SUFFIXES.find(s => norm.endsWith(s) && norm.length > s.length);
  return found ?? norm.slice(-3);
}
const fmtRate = full;

type TypeFilter = 'ALL' | 'REAL' | 'SIMULATION';

const LEG_STATUS_COLOR: Record<LegStatus, 'success' | 'error' | 'default'> = {
  FILLED:    'success',
  SIMULATED: 'success',
  FAILED:    'error',
};

function TradeDetailDialog({ tradeId, onClose }: { tradeId: number | null; onClose: () => void }) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [detail, setDetail] = useState<TradeDetail | null>(null);

  useEffect(() => {
    if (tradeId == null) return;
    setDetail(null);
    getTrade(tradeId).then((res) => setDetail(res.data));
  }, [tradeId]);

  return (
    <Dialog open={tradeId != null} onClose={onClose} maxWidth="lg" fullWidth fullScreen={fullScreen}>
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
          <>
            <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap', mb: 2, fontSize: '0.85rem' }}>
              <Box><Typography variant="caption" color="text.secondary">Order Size</Typography>
                <Typography variant="body2">${n(detail.orderSize, 2)}</Typography></Box>
              <Box><Typography variant="caption" color="text.secondary">Exp PnL</Typography>
                <Typography variant="body2" sx={{ color: Number(detail.expectedPnl) >= 0 ? 'success.main' : 'error.main' }}>
                  ${n(detail.expectedPnl, 2)}</Typography></Box>
              {detail.realProfit != null && (
                <Box><Typography variant="caption" color="text.secondary">Real Profit</Typography>
                  <Typography variant="body2" sx={{ color: Number(detail.realProfit) >= 0 ? 'success.main' : 'error.main' }}>
                    ${n(detail.realProfit, 2)}{detail.realProfitPercent != null ? ` (${n(detail.realProfitPercent, 4)}%)` : ''}</Typography></Box>
              )}
            </Box>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>#</TableCell>
                  <TableCell>Pair</TableCell>
                  <TableCell>Direction</TableCell>
                  <TableCell align="right">Quote Price</TableCell>
                  <TableCell align="right">Volume</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Order ID</TableCell>
                  <TableCell align="right">Rates</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {detail.legs.map((leg) => (
                  <TableRow key={leg.legIndex}>
                    <TableCell>{leg.legIndex}</TableCell>
                    <TableCell>{leg.pair}</TableCell>
                    <TableCell>
                      <Chip label={leg.direction} color={leg.direction === 'BUY' ? 'success' : 'warning'}
                        size="small" variant="outlined" />
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{full(leg.price)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{full(leg.volume)}</TableCell>
                    <TableCell>
                      <Chip label={leg.status} color={LEG_STATUS_COLOR[leg.status]} size="small" />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'text.secondary' }}>
                      {leg.orderId ?? '—'}
                    </TableCell>
                    <TableCell align="right" sx={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'text.secondary', whiteSpace: 'nowrap' }}>
                      {leg.quoteRate != null && leg.quoteRate > 0
                        ? `${leg.pair.replace('/', '')}: ${fmtRate(leg.quoteRate)}`
                        : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface TradesProps {
  // Last 20 trades, newest first, pushed over the dashboard WebSocket - merged into the
  // full REST-loaded list below so a new trade appears at the top live, no refresh needed.
  liveTrades?: Trade[];
}

export default function Trades({ liveTrades }: TradesProps) {
  const [trades, setTrades] = useState<Trade[]>([]);
  const [selectedTradeId, setSelectedTradeId] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [typeFilter, setTypeFilter] = useSessionState<TypeFilter>('trades:typeFilter', 'ALL');
  const [exchangeFilter, setExchangeFilter] = useSessionState('trades:exchangeFilter', 'ALL');
  const [dirFilter, setDirFilter] = useSessionState('trades:dirFilter', 'ALL');
  const [triangleFilter, setTriangleFilter] = useSessionState('trades:triangleFilter', 'ALL');
  const [page, setPage] = useSessionState('trades:page', 0);
  const [rowsPerPage, setRowsPerPage] = useSessionState('trades:rowsPerPage', 25);

  const exchanges = useMemo(() => [...new Set(trades.map(t => t.exchange))].sort(), [trades]);
  const directions = useMemo(() => [...new Set(trades.map(t => t.direction))].sort(), [trades]);
  const triangles = useMemo(() => {
    const seen = new Map<number, { label: string }>();
    trades.forEach(t => {
      if (t.triangleDisplayOrder != null && !seen.has(t.triangleDisplayOrder)) {
        const pairs = t.pair1 && t.pair2 && t.pair3 ? `${t.pair1}/${t.pair2}/${t.pair3}` : '';
        seen.set(t.triangleDisplayOrder, { label: `#${t.triangleDisplayOrder}${pairs ? ` — ${pairs}` : ''}` });
      }
    });
    return [...seen.entries()].sort((a, b) => a[0] - b[0]);
  }, [trades]);

  const loadTrades = () => getTrades().then((res) => setTrades(res.data));

  useEffect(() => { loadTrades(); }, []);

  // Trades never change after creation (see TradeController - no update endpoint), so
  // merging just means prepending whichever ids we haven't seen yet; anything already
  // loaded is left untouched, and new trades slot in above it without a refetch.
  useEffect(() => {
    if (!liveTrades?.length) return;
    setTrades((prev) => {
      const known = new Set(prev.map((t) => t.id));
      const fresh = liveTrades.filter((t) => !known.has(t.id));
      return fresh.length ? [...fresh, ...prev] : prev;
    });
  }, [liveTrades]);

  const handleDeleteSimulations = async () => {
    setDeleting(true);
    try { await deleteSimulationTrades(); await loadTrades(); setPage(0); }
    finally { setDeleting(false); }
  };

  const filteredTrades = trades.filter(t => {
    if (typeFilter === 'SIMULATION' && t.status !== 'SIMULATION') return false;
    if (typeFilter === 'REAL' && t.status === 'SIMULATION') return false;
    if (exchangeFilter !== 'ALL' && t.exchange !== exchangeFilter) return false;
    if (dirFilter !== 'ALL' && t.direction !== dirFilter) return false;
    if (triangleFilter !== 'ALL' && String(t.triangleDisplayOrder) !== triangleFilter) return false;
    return true;
  });

  const visibleTrades = filteredTrades.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage);

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h5">Trades</Typography>
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
          <FormControl size="small" sx={{ minWidth: 150 }}>
            <InputLabel>Type</InputLabel>
            <Select
              label="Type"
              value={typeFilter}
              onChange={e => { setTypeFilter(e.target.value as TypeFilter); setPage(0); }}
            >
              <MenuItem value="ALL">All</MenuItem>
              <MenuItem value="REAL">Real Trade</MenuItem>
              <MenuItem value="SIMULATION">Simulation</MenuItem>
            </Select>
          </FormControl>
          <FormControl size="small" sx={{ minWidth: 140 }}>
            <InputLabel>Exchange</InputLabel>
            <Select
              label="Exchange"
              value={exchangeFilter}
              onChange={e => { setExchangeFilter(e.target.value); setPage(0); }}
            >
              <MenuItem value="ALL">All</MenuItem>
              {exchanges.map(ex => <MenuItem key={ex} value={ex}>{ex}</MenuItem>)}
            </Select>
          </FormControl>
          <FormControl size="small" sx={{ minWidth: 120 }}>
            <InputLabel>Dir</InputLabel>
            <Select
              label="Dir"
              value={dirFilter}
              onChange={e => { setDirFilter(e.target.value); setPage(0); }}
            >
              <MenuItem value="ALL">All</MenuItem>
              {directions.map(d => <MenuItem key={d} value={d}>{d}</MenuItem>)}
            </Select>
          </FormControl>
          <FormControl size="small" sx={{ minWidth: 200 }}>
            <InputLabel>Triangle</InputLabel>
            <Select
              label="Triangle"
              value={triangleFilter}
              onChange={e => { setTriangleFilter(e.target.value); setPage(0); }}
            >
              <MenuItem value="ALL">All</MenuItem>
              {triangles.map(([order, { label }]) => (
                <MenuItem key={order} value={String(order)}>{label}</MenuItem>
              ))}
            </Select>
          </FormControl>
          <Button variant="outlined" color="error" size="small"
            onClick={handleDeleteSimulations} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete Simulations'}
          </Button>
        </Box>
      </Box>
      <Paper>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 420 }}>
            <TableHead>
              <TableRow>
                <TableCell>Time</TableCell>
                <TableCell sx={{ width: 90, pr: 0.5 }}>Exchange</TableCell>
                <TableCell sx={{ minWidth: 190, whiteSpace: 'nowrap', pl: 1.5 }}>Triangle</TableCell>
                <TableCell>Dir</TableCell>
                <TableCell align="right">Spread</TableCell>
                <TableCell align="right">PnL</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Profit %</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>Order Size</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>Exp PnL</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Latency</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {visibleTrades.map((t) => (
                <TableRow key={t.id} hover sx={{ cursor: 'pointer' }}
                  onClick={() => setSelectedTradeId(t.id)}>
                  <TableCell>{new Date(t.time + 'Z').toLocaleString()}</TableCell>
                  <TableCell sx={{ width: 90, pr: 0.5 }}>{t.exchange}</TableCell>
                  <TableCell sx={{ minWidth: 190, whiteSpace: 'nowrap', pl: 1.5 }}>
                    {t.pair1 && t.pair2 && t.pair3 ? `${t.pair1}/${t.pair2}/${t.pair3}` : '—'}
                  </TableCell>
                  <TableCell>{t.direction}</TableCell>
                  <TableCell align="right">{n(t.spread, 5)}</TableCell>
                  <TableCell align="right" sx={{ color: Number(t.pnl) >= 0 ? 'success.main' : 'error.main' }}>
                    ${n(t.pnl, 2)}
                  </TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' }, color: Number(t.profitPercent) >= 0 ? 'success.main' : 'error.main' }}>
                    {n(t.profitPercent, 4)}%
                  </TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>${n(t.orderSize, 0)}</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' }, color: Number(t.expectedPnl) >= 0 ? 'success.main' : 'error.main' }}>
                    ${n(t.expectedPnl, 2)}
                  </TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{n(t.latencyMs, 0)} ms</TableCell>
                  <TableCell>
                    <Chip label={t.status}
                      color={t.status === 'FILLED' ? 'success' : t.status === 'SIMULATION' ? 'info' : 'default'}
                      size="small" />
                  </TableCell>
                </TableRow>
              ))}
              {trades.length === 0 && (
                <TableRow>
                  <TableCell colSpan={11} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No trades yet
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={filteredTrades.length}
          page={page}
          rowsPerPage={rowsPerPage}
          rowsPerPageOptions={[10, 25, 50, 100]}
          onPageChange={(_, p) => setPage(p)}
          onRowsPerPageChange={(e) => { setRowsPerPage(+e.target.value); setPage(0); }}
          showFirstButton
          showLastButton
        />
      </Paper>

      <TradeDetailDialog tradeId={selectedTradeId} onClose={() => setSelectedTradeId(null)} />
    </Container>
  );
}
