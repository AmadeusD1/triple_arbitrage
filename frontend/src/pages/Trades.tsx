import { useEffect, useState } from 'react';
import {
  Box, Button, Chip, CircularProgress, Container, Dialog, DialogContent, DialogTitle,
  IconButton, Paper, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { getTrade, deleteSimulationTrades } from '../api/rest';
import type { Trade, TradeDetail, LegStatus } from '../types';

interface Props { trades: Trade[] }

const LEG_STATUS_COLOR: Record<LegStatus, 'success' | 'error' | 'default'> = {
  FILLED:    'success',
  SIMULATED: 'success',
  FAILED:    'error',
};

function TradeDetailDialog({ tradeId, onClose }: { tradeId: number | null; onClose: () => void }) {
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
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function Trades({ trades }: Props) {
  const [selectedTradeId, setSelectedTradeId] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);

  const handleDeleteSimulations = async () => {
    setDeleting(true);
    try { await deleteSimulationTrades(); } finally { setDeleting(false); }
  };

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h5">Trades</Typography>
        <Button variant="outlined" color="error" size="small"
          onClick={handleDeleteSimulations} disabled={deleting}>
          {deleting ? 'Deleting…' : 'Delete Simulations'}
        </Button>
      </Box>
      <Paper>
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
                <TableRow key={t.id} hover sx={{ cursor: 'pointer' }}
                  onClick={() => setSelectedTradeId(t.id)}>
                  <TableCell>{new Date(t.time + 'Z').toLocaleString()}</TableCell>
                  <TableCell>{t.direction}</TableCell>
                  <TableCell align="right">{t.spread.toFixed(5)}</TableCell>
                  <TableCell align="right" sx={{ color: t.pnl >= 0 ? 'success.main' : 'error.main' }}>
                    ${t.pnl.toFixed(2)}
                  </TableCell>
                  <TableCell align="right">{t.latencyMs.toFixed(0)} ms</TableCell>
                  <TableCell>
                    <Chip label={t.status}
                      color={t.status === 'FILLED' ? 'success' : t.status === 'SIMULATION' ? 'info' : 'default'}
                      size="small" />
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

      <TradeDetailDialog tradeId={selectedTradeId} onClose={() => setSelectedTradeId(null)} />
    </Container>
  );
}
