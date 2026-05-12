import { useEffect, useState } from 'react';
import {
  Chip, Container, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from '@mui/material';
import { getOpenOrders } from '../api/rest';
import type { OpenOrder } from '../types';

export default function OpenOrders() {
  const [orders, setOrders] = useState<OpenOrder[]>([]);

  useEffect(() => {
    const load = () => getOpenOrders().then((r) => setOrders(r.data));
    void load();
    const t = setInterval(() => void load(), 5_000);
    return () => clearInterval(t);
  }, []);

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 2 }}>Open Orders</Typography>
      <Paper>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 340 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Order ID</TableCell>
                <TableCell>Pair</TableCell>
                <TableCell>Side</TableCell>
                <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Type</TableCell>
                <TableCell align="right">Price</TableCell>
                <TableCell align="right">Volume</TableCell>
                <TableCell align="right">Filled</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {orders.map((o) => (
                <TableRow key={o.txid}>
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
                </TableRow>
              ))}
              {orders.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No open orders
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>
    </Container>
  );
}
