import {
  Container, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import type { PriceSnapshot } from '../types';

interface Props { prices: PriceSnapshot[] }

export default function Prices({ prices }: Props) {
  const sorted = [...prices].sort((a, b) => a.pair.localeCompare(b.pair));

  return (
    <Container maxWidth="md" sx={{ py: 3 }}>
      <Typography variant="h5" gutterBottom>Live Prices</Typography>
      <Paper>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Exchange</TableCell>
                <TableCell>Pair</TableCell>
                <TableCell align="right">Bid</TableCell>
                <TableCell align="right">Ask</TableCell>
                <TableCell align="right">Spread %</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map((p) => {
                const spread = ((p.ask - p.bid) / p.ask) * 100;
                return (
                  <TableRow key={`${p.exchange}-${p.pair}`}>
                    <TableCell sx={{ color: 'text.secondary' }}>{p.exchange}</TableCell>
                    <TableCell sx={{ fontWeight: 500 }}>{p.pair}</TableCell>
                    <TableCell align="right">{p.bid.toFixed(5)}</TableCell>
                    <TableCell align="right">{p.ask.toFixed(5)}</TableCell>
                    <TableCell align="right" sx={{ color: 'text.secondary' }}>
                      {spread.toFixed(5)}%
                    </TableCell>
                  </TableRow>
                );
              })}
              {sorted.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    Waiting for feed data…
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
