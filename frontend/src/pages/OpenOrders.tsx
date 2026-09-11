import { useEffect, useState } from 'react';
import {
  Alert, Button, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle,
  Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { cancelOpenOrder, getOpenOrders } from '../api/rest';
import type { OpenOrder } from '../types';

export default function OpenOrders() {
  const [orders, setOrders] = useState<OpenOrder[]>([]);
  const [confirmTarget, setConfirmTarget] = useState<OpenOrder | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [result, setResult] = useState<{ open: boolean; success: boolean; message: string }>(
    { open: false, success: false, message: '' },
  );

  useEffect(() => {
    const load = () => getOpenOrders().then((r) => setOrders(r.data));
    void load();
    const t = setInterval(() => void load(), 5_000);
    return () => clearInterval(t);
  }, []);

  const handleCancel = async () => {
    if (!confirmTarget) return;
    setCancelling(true);
    try {
      const res = await cancelOpenOrder(confirmTarget.exchange, confirmTarget.txid, confirmTarget.pair);
      if (res.data.success) {
        setResult({ open: true, success: true, message: `Order ${confirmTarget.txid} cancelled on ${confirmTarget.exchange}.` });
        setOrders((prev) => prev.filter((o) => o.txid !== confirmTarget.txid));
      } else {
        setResult({ open: true, success: false, message: res.data.message ?? 'Cancel rejected' });
      }
    } catch {
      setResult({ open: true, success: false, message: 'Cancel request failed — could not reach the server.' });
    } finally {
      setCancelling(false);
      setConfirmTarget(null);
    }
  };

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 2 }}>Open Orders</Typography>
      <Paper>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 340 }}>
            <TableHead>
              <TableRow>
                <TableCell>Exchange</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Order ID</TableCell>
                <TableCell>Pair</TableCell>
                <TableCell>Side</TableCell>
                <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Type</TableCell>
                <TableCell align="right">Price</TableCell>
                <TableCell align="right">Volume</TableCell>
                <TableCell align="right">Filled</TableCell>
                <TableCell>Status</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {orders.map((o) => (
                <TableRow key={o.txid}>
                  <TableCell>{o.exchange}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'text.secondary', display: { xs: 'none', md: 'table-cell' } }}>
                    {o.txid}
                  </TableCell>
                  <TableCell>{o.pair}</TableCell>
                  <TableCell>
                    <Chip label={o.side.toUpperCase()} size="small" variant="outlined"
                      color={o.side === 'buy' ? 'success' : 'warning'} />
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{o.orderType}</TableCell>
                  <TableCell align="right">{o.price.toFixed(5)}</TableCell>
                  <TableCell align="right">{o.volume.toFixed(4)}</TableCell>
                  <TableCell align="right">{o.volumeFilled.toFixed(4)}</TableCell>
                  <TableCell>
                    <Chip label={o.status} size="small" color="info" />
                  </TableCell>
                  <TableCell align="right">
                    <Button size="small" color="error" variant="outlined" onClick={() => setConfirmTarget(o)}>
                      Cancel
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {orders.length === 0 && (
                <TableRow>
                  <TableCell colSpan={10} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No open orders
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      <Dialog open={confirmTarget != null} onClose={() => setConfirmTarget(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Cancel Order</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 1 }}>This will cancel a real open order.</Alert>
          {confirmTarget && (
            <Typography>
              Cancel {confirmTarget.side.toUpperCase()} {confirmTarget.volume.toFixed(4)} {confirmTarget.pair} on {confirmTarget.exchange}?
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmTarget(null)}>Back</Button>
          <Button variant="contained" color="error" disabled={cancelling} onClick={() => void handleCancel()}>
            Confirm Cancel
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={result.open} onClose={() => setResult((r) => ({ ...r, open: false }))} maxWidth="xs" fullWidth>
        <DialogTitle>{result.success ? 'Order Cancelled' : 'Cancel Failed'}</DialogTitle>
        <DialogContent>
          <Alert severity={result.success ? 'success' : 'error'}>{result.message}</Alert>
        </DialogContent>
        <DialogActions>
          <Button variant="contained" onClick={() => setResult((r) => ({ ...r, open: false }))}>Close</Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}
