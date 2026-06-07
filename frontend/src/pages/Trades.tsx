import { useEffect, useState } from 'react';
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
    <Dialog open={tradeId != null} onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen}>
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
                <Typography variant="body2">${detail.orderSize.toFixed(2)}</Typography></Box>
              <Box><Typography variant="caption" color="text.secondary">Exp PnL</Typography>
                <Typography variant="body2" sx={{ color: detail.expectedPnl >= 0 ? 'success.main' : 'error.main' }}>
                  ${detail.expectedPnl.toFixed(2)}</Typography></Box>
              {detail.realProfit != null && (
                <Box><Typography variant="caption" color="text.secondary">Real Profit</Typography>
                  <Typography variant="body2" sx={{ color: detail.realProfit >= 0 ? 'success.main' : 'error.main' }}>
                    ${detail.realProfit.toFixed(2)}{detail.realProfitPercent != null ? ` (${detail.realProfitPercent.toFixed(4)}%)` : ''}</Typography></Box>
              )}
            </Box>
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
                      <Chip label={leg.direction} color={leg.direction === 'BUY' ? 'success' : 'warning'}
                        size="small" variant="outlined" />
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
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function Trades() {
  const [trades, setTrades] = useState<Trade[]>([]);
  const [selectedTradeId, setSelectedTradeId] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [typeFilter, setTypeFilter] = useSessionState<TypeFilter>('trades:typeFilter', 'ALL');
  const [page, setPage] = useSessionState('trades:page', 0);
  const [rowsPerPage, setRowsPerPage] = useSessionState('trades:rowsPerPage', 25);

  const loadTrades = () => getTrades().then((res) => setTrades(res.data));

  useEffect(() => { loadTrades(); }, []);

  const handleDeleteSimulations = async () => {
    setDeleting(true);
    try { await deleteSimulationTrades(); await loadTrades(); setPage(0); }
    finally { setDeleting(false); }
  };

  const filteredTrades = trades.filter(t => {
    if (typeFilter === 'SIMULATION') return t.status === 'SIMULATION';
    if (typeFilter === 'REAL') return t.status !== 'SIMULATION';
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
                <TableCell>Exchange</TableCell>
                <TableCell>Dir</TableCell>
                <TableCell align="right">Spread</TableCell>
                <TableCell align="right">PnL</TableCell>
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
                  <TableCell>{t.exchange}</TableCell>
                  <TableCell>{t.direction}</TableCell>
                  <TableCell align="right">{t.spread.toFixed(5)}</TableCell>
                  <TableCell align="right" sx={{ color: t.pnl >= 0 ? 'success.main' : 'error.main' }}>
                    ${t.pnl.toFixed(2)}
                  </TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>${t.orderSize.toFixed(0)}</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' }, color: t.expectedPnl >= 0 ? 'success.main' : 'error.main' }}>
                    ${t.expectedPnl.toFixed(2)}
                  </TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t.latencyMs.toFixed(0)} ms</TableCell>
                  <TableCell>
                    <Chip label={t.status}
                      color={t.status === 'FILLED' ? 'success' : t.status === 'SIMULATION' ? 'info' : 'default'}
                      size="small" />
                  </TableCell>
                </TableRow>
              ))}
              {trades.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ py: 3, color: 'text.secondary' }}>
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
