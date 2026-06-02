import { useEffect, useState } from 'react';
import {
  Container, Paper, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Typography,
} from '@mui/material';
import { getPositions } from '../api/rest';
import type { BalanceEntry } from '../types';

export default function Positions() {
  const [balances, setBalances] = useState<BalanceEntry[]>([]);

  useEffect(() => {
    const load = () => getPositions().then((r) => setBalances(r.data));
    void load();
    const t = setInterval(() => void load(), 5_000);
    return () => clearInterval(t);
  }, []);

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 2 }}>Positions</Typography>
      <Paper>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Exchange</TableCell>
                <TableCell>Currency</TableCell>
                <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Asset Key</TableCell>
                <TableCell align="right">Balance</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {balances.map((b) => (
                <TableRow key={`${b.exchange}-${b.assetKey}`}>
                  <TableCell>{b.exchange}</TableCell>
                  <TableCell>{b.currency}</TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontFamily: 'monospace', display: { xs: 'none', sm: 'table-cell' } }}>{b.assetKey}</TableCell>
                  <TableCell align="right">{b.amount.toFixed(4)}</TableCell>
                </TableRow>
              ))}
              {balances.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No balances available
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
