import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Container, Dialog, DialogActions, DialogContent,
  DialogTitle, IconButton, Paper, Snackbar, Switch, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, ToggleButton, ToggleButtonGroup,
  Tooltip, Typography, useTheme, useMediaQuery,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import { getTriangles, createTriangle, updateTriangle, deleteTriangle, manualTrade } from '../api/rest';
import type { CycleDirection, OrderLeg, PriceSnapshot, TriangleConfig, TriangleStatus } from '../types';

interface Props { prices: PriceSnapshot[] }

type TrianglePayload = Omit<TriangleConfig, 'id' | 'hits' | 'totalProfitUsd'>;

const EMPTY_FORM: TrianglePayload = {
  exchange: 'KRAKEN', pair1: '', pair2: '', pair3: '',
  minProfitUsd: 10, minProfitPercent: 0.01, status: 'ACTIVE', cycle: 'BBS',
};

interface SnackState { open: boolean; message: string; severity: 'success' | 'info' | 'error' }

const CYCLE_DESCRIPTIONS: Record<string, string> = {
  BBS: 'BUY / BUY / SELL',
  BSS: 'BUY / SELL / SELL',
  BSB: 'BUY / SELL / BUY',
  SBS: 'SELL / BUY / SELL',
};

const CYCLE_DIRS: Record<string, string[]> = {
  BBS: ['BUY', 'BUY', 'SELL'],
  BSS: ['BUY', 'SELL', 'SELL'],
  BSB: ['BUY', 'SELL', 'BUY'],
  SBS: ['SELL', 'BUY', 'SELL'],
};

function computeLegs(t: TriangleConfig, cycle: CycleDirection, size: number, prices: PriceSnapshot[]): OrderLeg[] {
  const dirs = CYCLE_DIRS[cycle];
  return [t.pair1, t.pair2, t.pair3].map((pair, i) => {
    const snap = prices.find(p => p.pair === pair);
    const price = snap ? (dirs[i] === 'BUY' ? snap.ask : snap.bid) : 0;
    return { legIndex: i + 1, pair, direction: dirs[i], price, volume: price > 0 ? size / price : 0 };
  });
}

export default function Triangles({ prices }: Props) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [triangles, setTriangles] = useState<TriangleConfig[]>([]);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<TrianglePayload>(EMPTY_FORM);

  const [tradeTarget, setTradeTarget] = useState<TriangleConfig | null>(null);
  const [tradeCycle, setTradeCycle] = useState<CycleDirection>('BBS');
  const [legs, setLegs] = useState<OrderLeg[]>([]);
  const [snack, setSnack] = useState<SnackState>({ open: false, message: '', severity: 'success' });

  const load = () => getTriangles().then((res) => setTriangles(res.data.slice().sort((a, b) => a.id - b.id)));
  useEffect(() => { void load(); }, []);

  const DEFAULT_SIZE = 10_000;

  const openTradeDialog = (t: TriangleConfig) => {
    setTradeTarget(t);
    const cycle = (t.cycle as CycleDirection) ?? 'BBS';
    setTradeCycle(cycle);
    setLegs(computeLegs(t, cycle, DEFAULT_SIZE, prices));
  };

  const handleCycleChange = (cycle: CycleDirection) => {
    setTradeCycle(cycle);
    if (tradeTarget) setLegs(computeLegs(tradeTarget, cycle, DEFAULT_SIZE, prices));
  };

  const updateLeg = (index: number, field: 'price' | 'volume', value: number) => {
    setLegs(prev => prev.map(l => l.legIndex === index ? { ...l, [field]: value } : l));
  };

  const handleExecuteTrade = async () => {
    if (!tradeTarget) return;
    try {
      const res = await manualTrade(tradeTarget.id, tradeCycle, legs);
      const { status, pnl } = res.data;
      const severity = status === 'FILLED' ? 'success' : status === 'SIMULATION' ? 'info' : 'error';
      const label = (status === 'FILLED' || status === 'SIMULATION')
        ? `Trade ${status} — PnL: $${pnl.toFixed(2)}`
        : `Trade ${status}`;
      setSnack({ open: true, message: label, severity });
    } catch {
      setSnack({ open: true, message: 'Trade request failed', severity: 'error' });
    }
    setTradeTarget(null);
    void load();
  };

  const openCreate = () => { setEditingId(null); setForm(EMPTY_FORM); setDialogOpen(true); };
  const openEdit = (t: TriangleConfig) => {
    setEditingId(t.id);
    setForm({ exchange: t.exchange, pair1: t.pair1, pair2: t.pair2, pair3: t.pair3,
               minProfitUsd: t.minProfitUsd, minProfitPercent: t.minProfitPercent, status: t.status, cycle: t.cycle });
    setDialogOpen(true);
  };
  const handleSave = async () => {
    if (editingId == null) await createTriangle(form); else await updateTriangle(editingId, form);
    setDialogOpen(false); void load();
  };
  const handleDelete = async (id: number) => { await deleteTriangle(id); void load(); };
  const handleToggleStatus = async (t: TriangleConfig) => {
    const next: TriangleStatus = t.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    await updateTriangle(t.id, { ...t, status: next }); void load();
  };

  const textField = (key: keyof TrianglePayload, label: string, type = 'text') => (
    <TextField key={key} label={label} type={type} fullWidth size="small" value={form[key]}
      onChange={(e) => setForm(f => ({ ...f, [key]: type === 'number' ? Number(e.target.value) : e.target.value }))} />
  );

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5">Triangle Configurations</Typography>
        <Button variant="contained" onClick={openCreate}>Add Triangle</Button>
      </Box>

      <Paper>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 380 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Exchange</TableCell>
                <TableCell>Pairs</TableCell>
                <TableCell>Cycle</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Min %</TableCell>
                <TableCell align="right">Min USD</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Hits</TableCell>
                <TableCell align="right">Total PnL</TableCell>
                <TableCell align="center">Active</TableCell>
                <TableCell align="center">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {triangles.map((t) => (
                <TableRow key={t.id}>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t.exchange}</TableCell>
                  <TableCell><Chip label={`${t.pair1} / ${t.pair2} / ${t.pair3}`} size="small" variant="outlined" /></TableCell>
                  <TableCell>
                    <Tooltip title={CYCLE_DESCRIPTIONS[t.cycle] ?? t.cycle} placement="right">
                      <Chip label={t.cycle} size="small" />
                    </Tooltip>
                  </TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t.minProfitPercent.toFixed(5)}</TableCell>
                  <TableCell align="right">${t.minProfitUsd.toFixed(2)}</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t.hits}</TableCell>
                  <TableCell align="right" sx={{ color: t.totalProfitUsd >= 0 ? 'success.main' : 'error.main' }}>
                    ${t.totalProfitUsd.toFixed(2)}
                  </TableCell>
                  <TableCell align="center">
                    <Switch size="small" checked={t.status === 'ACTIVE'} onChange={() => void handleToggleStatus(t)} />
                  </TableCell>
                  <TableCell align="center">
                    <IconButton size="small" color="success" onClick={() => openTradeDialog(t)}>
                      <PlayArrowIcon fontSize="small" />
                    </IconButton>
                    <IconButton size="small" onClick={() => openEdit(t)}><EditIcon fontSize="small" /></IconButton>
                    <IconButton size="small" color="error" onClick={() => void handleDelete(t.id)}><DeleteIcon fontSize="small" /></IconButton>
                  </TableCell>
                </TableRow>
              ))}
              {triangles.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ py: 3, color: 'text.secondary' }}>No triangles configured</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      {/* Edit / Add dialog */}
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
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block' }}>Cycle</Typography>
              <ToggleButtonGroup value={form.cycle} exclusive size="small" sx={{ flexWrap: 'wrap' }}
                onChange={(_, v) => { if (v) setForm(f => ({ ...f, cycle: v })); }}>
                {(['BBS', 'BSS', 'BSB', 'SBS'] as const).map(c => (
                  <ToggleButton key={c} value={c} sx={{ flexDirection: 'column', px: 2, py: 0.5 }}>
                    <span>{c}</span>
                    <Typography variant="caption" sx={{ opacity: 0.7 }}>{CYCLE_DESCRIPTIONS[c]}</Typography>
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => void handleSave()}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* Manual trade dialog */}
      <Dialog open={tradeTarget != null} onClose={() => setTradeTarget(null)} maxWidth="sm" fullWidth fullScreen={fullScreen}>
        <DialogTitle>Manual Trade — {tradeTarget?.pair1} / {tradeTarget?.pair2} / {tradeTarget?.pair3}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            <ToggleButtonGroup value={tradeCycle} exclusive size="small" sx={{ flexWrap: 'wrap' }}
              onChange={(_, v) => { if (v) handleCycleChange(v as CycleDirection); }}>
              {(['BBS', 'BSS', 'BSB', 'SBS'] as const).map(c => (
                <ToggleButton key={c} value={c} sx={{ flexDirection: 'column', px: 2, py: 0.5 }}>
                  <span>{c}</span>
                  <Typography variant="caption" sx={{ opacity: 0.7 }}>{CYCLE_DESCRIPTIONS[c]}</Typography>
                </ToggleButton>
              ))}
            </ToggleButtonGroup>

            {/* Per-leg table */}
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>#</TableCell>
                  <TableCell>Pair</TableCell>
                  <TableCell>Direction</TableCell>
                  <TableCell>Price</TableCell>
                  <TableCell>Volume</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {legs.map((l) => (
                  <TableRow key={l.legIndex}>
                    <TableCell>{l.legIndex}</TableCell>
                    <TableCell>{l.pair}</TableCell>
                    <TableCell>
                      <Chip label={l.direction} size="small"
                        color={l.direction === 'BUY' ? 'success' : 'warning'} variant="outlined" />
                    </TableCell>
                    <TableCell>
                      <TextField type="number" size="small" value={l.price}
                        onChange={(e) => updateLeg(l.legIndex, 'price', Number(e.target.value))}
                        sx={{ width: { xs: '100%', sm: 110 } }} />
                    </TableCell>
                    <TableCell>
                      <TextField type="number" size="small" value={l.volume.toFixed(4)}
                        onChange={(e) => updateLeg(l.legIndex, 'volume', Number(e.target.value))}
                        sx={{ width: { xs: '100%', sm: 110 } }} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>

            <Alert severity="info" sx={{ fontSize: '0.75rem' }}>
              Balance and risk limits are checked server-side against the order size above.
            </Alert>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTradeTarget(null)}>Cancel</Button>
          <Button variant="contained" color="success" onClick={() => void handleExecuteTrade()}>
            Execute Trade
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar open={snack.open} autoHideDuration={5000}
        onClose={() => setSnack(s => ({ ...s, open: false }))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
        <Alert severity={snack.severity} onClose={() => setSnack(s => ({ ...s, open: false }))}>
          {snack.message}
        </Alert>
      </Snackbar>
    </Container>
  );
}
