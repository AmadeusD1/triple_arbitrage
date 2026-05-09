import { useState } from 'react';
import {
  Box, Button, Chip, Container, Paper, Table, TableBody, TableCell,
  TableContainer, TableHead, TablePagination, TableRow, Typography,
} from '@mui/material';
import { clearMissedOpportunities } from '../api/rest';
import type { MissedOpportunity } from '../types';

interface Props { rows: MissedOpportunity[] }

const ROWS_PER_PAGE = 20;

function rejectionChip(rejection: string) {
  if (rejection === 'REJECTED_BALANCE') return <Chip label="Balance" color="warning" size="small" />;
  if (rejection === 'REJECTED_RISK')    return <Chip label="Risk"    color="error"   size="small" />;
  return                                       <Chip label="Profit"                  size="small" />;
}

export default function MissedOpportunities({ rows }: Props) {
  const [page, setPage] = useState(0);

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
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Time</TableCell>
                <TableCell>Exchange</TableCell>
                <TableCell>Pairs</TableCell>
                <TableCell>Cycle</TableCell>
                <TableCell align="right">Edge</TableCell>
                <TableCell align="right">Order Size</TableCell>
                <TableCell align="center">Rejection</TableCell>
                <TableCell>Reason</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {slice.map((r) => (
                <TableRow key={r.id} hover>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {new Date(r.time + 'Z').toLocaleString()}
                  </TableCell>
                  <TableCell>{r.exchange}</TableCell>
                  <TableCell>
                    <Chip label={`${r.pair1} / ${r.pair2} / ${r.pair3}`} size="small" variant="outlined" />
                  </TableCell>
                  <TableCell>{r.cycle}</TableCell>
                  <TableCell align="right">{r.edge.toFixed(5)}</TableCell>
                  <TableCell align="right">${r.orderSize.toFixed(2)}</TableCell>
                  <TableCell align="center">{rejectionChip(r.rejection)}</TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>
                    {r.reason ?? '—'}
                  </TableCell>
                </TableRow>
              ))}
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ py: 3, color: 'text.secondary' }}>
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
        />
      </Paper>
    </Container>
  );
}
