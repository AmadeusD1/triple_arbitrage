import {
  Container, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';

interface Props { rates: Record<string, number> }

export default function CurrencyRates({ rates }: Props) {
  const sorted = Object.entries(rates).sort(([a], [b]) => a.localeCompare(b));

  return (
    <Container maxWidth="sm" sx={{ py: 3 }}>
      <Typography variant="h5" gutterBottom>FX Rates</Typography>
      <Paper>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Pair</TableCell>
                <TableCell align="right">USD Rate</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map(([pair, rate]) => (
                <TableRow key={pair}>
                  <TableCell sx={{ fontWeight: 500 }}>{pair}</TableCell>
                  <TableCell align="right">{rate.toFixed(6)}</TableCell>
                </TableRow>
              ))}
              {sorted.length === 0 && (
                <TableRow>
                  <TableCell colSpan={2} align="center" sx={{ py: 3, color: 'text.secondary' }}>
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
