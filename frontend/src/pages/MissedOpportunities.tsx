import { useState } from 'react';
import { useSessionState } from '../hooks/useSessionState';
import {
  Box, Button, Chip, Container, Dialog, DialogContent, DialogTitle,
  IconButton, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TablePagination, TableRow, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { clearMissedOpportunities } from '../api/rest';
import type { MissedOpportunity } from '../types';

interface Props { rows: MissedOpportunity[] }

const ROWS_PER_PAGE = 20;

function rejectionChip(rejection: string) {
  if (rejection === 'REJECTED_BALANCE') return <Chip label="Balance" color="warning" size="small" />;
  if (rejection === 'REJECTED_RISK')    return <Chip label="Risk"    color="error"   size="small" />;
  return                                       <Chip label="Profit"                  size="small" />;
}

function LegDetailDialog({ row, onClose }: { row: MissedOpportunity | null; onClose: () => void }) {
  if (!row) return null;
  const legs = [
    { pair: row.pair1, price: row.leg1Price, quantity: row.leg1Volume },
    { pair: row.pair2, price: row.leg2Price, quantity: row.leg2Volume },
    { pair: row.pair3, price: row.leg3Price, quantity: row.leg3Volume },
  ];
  return (
    <Dialog open={row != null} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        Missed — {row.pair1} / {row.pair2} / {row.pair3}
        <IconButton size="small" onClick={onClose}><CloseIcon fontSize="small" /></IconButton>
      </DialogTitle>
      <DialogContent>
        <Box sx={{ display: 'flex', gap: 3, mb: 2, flexWrap: 'wrap' }}>
          <Box><Typography variant="caption" color="text.secondary">Order Size</Typography>
            <Typography variant="body2">${row.orderSize.toFixed(2)}</Typography></Box>
          <Box><Typography variant="caption" color="text.secondary">Exp PnL</Typography>
            <Typography variant="body2" sx={{ color: row.expectedPnl >= 0 ? 'success.main' : 'error.main' }}>
              ${row.expectedPnl.toFixed(2)}</Typography></Box>
          <Box><Typography variant="caption" color="text.secondary">Edge</Typography>
            <Typography variant="body2">{row.edge.toFixed(5)}</Typography></Box>
        </Box>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>#</TableCell>
                <TableCell>Pair</TableCell>
                <TableCell align="right">Price</TableCell>
                <TableCell align="right">Volume</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {legs.map((l, i) => (
                <TableRow key={i}>
                  <TableCell>{i + 1}</TableCell>
                  <TableCell>{l.pair}</TableCell>
                  <TableCell align="right">{l.price.toFixed(5)}</TableCell>
                  <TableCell align="right">{l.quantity.toFixed(4)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </DialogContent>
    </Dialog>
  );
}

export default function MissedOpportunities({ rows }: Props) {
  const [page, setPage] = useSessionState('missed:page', 0);
  const [selected, setSelected] = useState<MissedOpportunity | null>(null);

  const slice = rows.slice(page * ROWS_PER_PAGE, (page + 1) * ROWS_PER_PAGE);

  const handleClear = async () => {
    await clearMissedOpportunities();
    setPage(0);
  };

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h5">Missed Opportunities</Typography>
        <Button variant="outlined" color="error" size="small" onClick={() => void handleClear()}
          disabled={rows.length === 0}>
          Clear All
        </Button>
      </Box>

      <Paper>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 560 }}>
            <TableHead>
              <TableRow>
                <TableCell>Time</TableCell>
                <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Exchange</TableCell>
                <TableCell>Pairs</TableCell>
                <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Cycle</TableCell>
                <TableCell align="right">Edge</TableCell>
                <TableCell align="right">Order Size</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Exp PnL</TableCell>
                <TableCell align="center">Rejection</TableCell>
                <TableCell>Reason</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {slice.map((r) => (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => setSelected(r)}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {new Date(r.time + 'Z').toLocaleString()}
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{r.exchange}</TableCell>
                  <TableCell>
                    <Chip label={`${r.pair1} / ${r.pair2} / ${r.pair3}`} size="small" variant="outlined" />
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{r.cycle}</TableCell>
                  <TableCell align="right">{r.edge.toFixed(5)}</TableCell>
                  <TableCell align="right">${r.orderSize.toFixed(2)}</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' }, color: r.expectedPnl >= 0 ? 'success.main' : 'error.main' }}>
                    ${r.expectedPnl.toFixed(2)}
                  </TableCell>
                  <TableCell align="center">{rejectionChip(r.rejection)}</TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>
                    {r.reason ?? '—'}
                  </TableCell>
                </TableRow>
              ))}
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No missed opportunities recorded
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={rows.length}
          page={page}
          rowsPerPage={ROWS_PER_PAGE}
          rowsPerPageOptions={[ROWS_PER_PAGE]}
          onPageChange={(_, p) => setPage(p)}
          showFirstButton
          showLastButton
        />
      </Paper>
      <LegDetailDialog row={selected} onClose={() => setSelected(null)} />
    </Container>
  );
}
