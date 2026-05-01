import { useEffect, useState } from 'react';
import {
  Box, Button, Chip, Container, Dialog, DialogActions, DialogContent,
  DialogTitle, IconButton, Paper, Switch, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';
import { getTriangles, createTriangle, updateTriangle, deleteTriangle } from '../api/rest';
import type { TriangleConfig, TriangleStatus } from '../types';

type TrianglePayload = Omit<TriangleConfig, 'id' | 'hits' | 'totalProfitUsd'>;

const EMPTY_FORM: TrianglePayload = {
  exchange: 'KRAKEN',
  pair1: '',
  pair2: '',
  pair3: '',
  minProfitUsd: 0,
  minProfitPercent: 0.00025,
  status: 'ACTIVE',
};

export default function Triangles() {
  const [triangles, setTriangles] = useState<TriangleConfig[]>([]);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<TrianglePayload>(EMPTY_FORM);

  const load = () => getTriangles().then((res) => setTriangles(res.data));

  useEffect(() => { void load(); }, []);

  const openCreate = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (t: TriangleConfig) => {
    setEditingId(t.id);
    setForm({
      exchange: t.exchange,
      pair1: t.pair1,
      pair2: t.pair2,
      pair3: t.pair3,
      minProfitUsd: t.minProfitUsd,
      minProfitPercent: t.minProfitPercent,
      status: t.status,
    });
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (editingId == null) {
      await createTriangle(form);
    } else {
      await updateTriangle(editingId, form);
    }
    setDialogOpen(false);
    void load();
  };

  const handleDelete = async (id: number) => {
    await deleteTriangle(id);
    void load();
  };

  const handleToggleStatus = async (t: TriangleConfig) => {
    const next: TriangleStatus = t.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    await updateTriangle(t.id, { ...t, status: next });
    void load();
  };

  const textField = (key: keyof TrianglePayload, label: string, type = 'text') => (
    <TextField
      key={key}
      label={label}
      type={type}
      fullWidth
      size="small"
      value={form[key]}
      onChange={(e) =>
        setForm((f) => ({
          ...f,
          [key]: type === 'number' ? Number(e.target.value) : e.target.value,
        }))
      }
    />
  );

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5">Triangle Configurations</Typography>
        <Button variant="contained" onClick={openCreate}>Add Triangle</Button>
      </Box>

      <Paper>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Exchange</TableCell>
                <TableCell>Pairs</TableCell>
                <TableCell align="right">Min %</TableCell>
                <TableCell align="right">Min USD</TableCell>
                <TableCell align="right">Hits</TableCell>
                <TableCell align="right">Total PnL</TableCell>
                <TableCell align="center">Active</TableCell>
                <TableCell align="center">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {triangles.map((t) => (
                <TableRow key={t.id}>
                  <TableCell>{t.exchange}</TableCell>
                  <TableCell>
                    <Chip label={`${t.pair1} / ${t.pair2} / ${t.pair3}`} size="small" variant="outlined" />
                  </TableCell>
                  <TableCell align="right">{t.minProfitPercent.toFixed(5)}</TableCell>
                  <TableCell align="right">${t.minProfitUsd.toFixed(2)}</TableCell>
                  <TableCell align="right">{t.hits}</TableCell>
                  <TableCell align="right" sx={{ color: t.totalProfitUsd >= 0 ? 'success.main' : 'error.main' }}>
                    ${t.totalProfitUsd.toFixed(2)}
                  </TableCell>
                  <TableCell align="center">
                    <Switch
                      size="small"
                      checked={t.status === 'ACTIVE'}
                      onChange={() => void handleToggleStatus(t)}
                    />
                  </TableCell>
                  <TableCell align="center">
                    <IconButton size="small" onClick={() => openEdit(t)}>
                      <EditIcon fontSize="small" />
                    </IconButton>
                    <IconButton size="small" color="error" onClick={() => void handleDelete(t.id)}>
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </TableCell>
                </TableRow>
              ))}
              {triangles.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No triangles configured
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editingId == null ? 'Add Triangle' : 'Edit Triangle'}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            {textField('exchange', 'Exchange')}
            {textField('pair1', 'Pair 1 (e.g. EURUSD)')}
            {textField('pair2', 'Pair 2 (e.g. USDJPY)')}
            {textField('pair3', 'Pair 3 (e.g. EURJPY)')}
            {textField('minProfitPercent', 'Min Profit % (e.g. 0.00025)', 'number')}
            {textField('minProfitUsd', 'Min Profit USD', 'number')}
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => void handleSave()}>Save</Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}
