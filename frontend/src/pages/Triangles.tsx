import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Container, Dialog, DialogActions, DialogContent,
  DialogTitle, Divider, IconButton, MenuItem, Paper, Select, Snackbar, Switch,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField,
  ToggleButton, ToggleButtonGroup, Tooltip, Typography, useTheme, useMediaQuery,
  InputLabel, FormControl,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import EditIcon from '@mui/icons-material/Edit';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import {
  getTriangles, createTriangle, updateTriangle, deleteTriangle, manualTrade,
  getExchangeConfigs, createExchangeConfig, updateExchangeConfig, deleteExchangeConfig,
  startExchange, stopExchange,
} from '../api/rest';
import type { CycleDirection, ExchangeConfig, OrderLeg, PriceSnapshot, TriangleConfig, TriangleStatus } from '../types';

interface Props { prices: PriceSnapshot[]; exchangeRunning: Record<string, boolean> }

type TrianglePayload = Omit<TriangleConfig, 'id' | 'hits' | 'totalProfitUsd'>;
type ExchangePayload = Omit<ExchangeConfig, 'id' | 'createdAt'>;

const KNOWN_EXCHANGES = ['KRAKEN', 'BINANCE', 'BITSTAMP', 'COINBASE', 'BITFINEX', 'HTX', 'KUCOIN', 'BYBIT'];

const EMPTY_TRI: TrianglePayload = {
  exchange: 'KRAKEN', pair1: '', pair2: '', pair3: '',
  minProfitUsd: 10, minProfitPercent: 0.01, status: 'ACTIVE', cycle: 'BBS',
};

const EMPTY_EX: ExchangePayload = {
  exchange: 'BINANCE', enabled: false, simulation: true,
  apiKey: null, apiSecret: null, apiPassphrase: null, wsUrl: null,
  orderSizeUsd: 100000, positionLimitUsd: 10000, maxDailyLossUsd: -1000,
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
    return { legIndex: i + 1, pair, direction: dirs[i], price, quantity: price > 0 ? size / price : 0 };
  });
}

function maskKey(key: string | null): string {
  if (!key) return '—';
  if (key.length <= 8) return '••••••••';
  return key.slice(0, 4) + '••••••••' + key.slice(-4);
}

export default function Triangles({ prices, exchangeRunning }: Props) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));

  // ── Triangle state ─────────────────────────────────────────────
  const [triangles, setTriangles] = useState<TriangleConfig[]>([]);
  const [triDialogOpen, setTriDialogOpen] = useState(false);
  const [editingTriId, setEditingTriId] = useState<number | null>(null);
  const [triForm, setTriForm] = useState<TrianglePayload>(EMPTY_TRI);
  const [tradeTarget, setTradeTarget] = useState<TriangleConfig | null>(null);
  const [tradeCycle, setTradeCycle] = useState<CycleDirection>('BBS');
  const [legs, setLegs] = useState<OrderLeg[]>([]);

  // ── Exchange state ─────────────────────────────────────────────
  const [exchanges, setExchanges] = useState<ExchangeConfig[]>([]);
  const [exDialogOpen, setExDialogOpen] = useState(false);
  const [editingExId, setEditingExId] = useState<number | null>(null);
  const [exForm, setExForm] = useState<ExchangePayload>(EMPTY_EX);

  const [snack, setSnack] = useState<SnackState>({ open: false, message: '', severity: 'success' });

  const loadTriangles = () => getTriangles().then(r => setTriangles(r.data.slice().sort((a, b) => a.id - b.id)));
  const loadExchanges = () => getExchangeConfigs().then(r => setExchanges(r.data.slice().sort((a, b) => a.exchange.localeCompare(b.exchange))));

  useEffect(() => { void loadTriangles(); void loadExchanges(); }, []);

  // ── Exchange handlers ───────────────────────────────────────────
  const openCreateEx = () => { setEditingExId(null); setExForm(EMPTY_EX); setExDialogOpen(true); };
  const openEditEx   = (c: ExchangeConfig) => {
    setEditingExId(c.id);
    setExForm({ exchange: c.exchange, enabled: c.enabled, simulation: c.simulation,
                apiKey: c.apiKey, apiSecret: c.apiSecret,
                apiPassphrase: c.apiPassphrase, wsUrl: c.wsUrl, orderSizeUsd: c.orderSizeUsd,
                positionLimitUsd: c.positionLimitUsd, maxDailyLossUsd: c.maxDailyLossUsd });
    setExDialogOpen(true);
  };
  const handleSaveEx = async () => {
    try {
      if (editingExId == null) await createExchangeConfig(exForm);
      else                     await updateExchangeConfig(editingExId, exForm);
      setExDialogOpen(false);
      void loadExchanges();
    } catch {
      setSnack({ open: true, message: 'Failed to save exchange config', severity: 'error' });
    }
  };
  const handleDeleteEx    = async (id: number) => { await deleteExchangeConfig(id); void loadExchanges(); };
  const handleToggleEx    = async (c: ExchangeConfig) => {
    await updateExchangeConfig(c.id, { ...c, enabled: !c.enabled });
    void loadExchanges();
  };
  const handleToggleModOp = async (c: ExchangeConfig) => {
    await updateExchangeConfig(c.id, { ...c, simulation: !c.simulation });
    void loadExchanges();
  };
  const handleStartEx     = async (name: string) => { await startExchange(name); setSnack({ open: true, message: `${name} scan started`, severity: 'success' }); };
  const handleStopEx      = async (name: string) => { await stopExchange(name);  setSnack({ open: true, message: `${name} scan stopped`, severity: 'info' }); };

  // ── Triangle handlers ───────────────────────────────────────────
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
  const updateLeg = (index: number, field: 'price' | 'quantity', value: number) =>
    setLegs(prev => prev.map(l => l.legIndex === index ? { ...l, [field]: value } : l));
  const handleExecuteTrade = async () => {
    if (!tradeTarget) return;
    try {
      const res = await manualTrade(tradeTarget.id, tradeCycle, legs);
      const { status, pnl } = res.data;
      const severity = status === 'FILLED' ? 'success' : status === 'SIMULATION' ? 'info' : 'error';
      const label    = (status === 'FILLED' || status === 'SIMULATION') ? `Trade ${status} — PnL: $${pnl.toFixed(2)}` : `Trade ${status}`;
      setSnack({ open: true, message: label, severity });
    } catch {
      setSnack({ open: true, message: 'Trade request failed', severity: 'error' });
    }
    setTradeTarget(null);
    void loadTriangles();
  };
  const openCreateTri = () => { setEditingTriId(null); setTriForm(EMPTY_TRI); setTriDialogOpen(true); };
  const openEditTri   = (t: TriangleConfig) => {
    setEditingTriId(t.id);
    setTriForm({ exchange: t.exchange, pair1: t.pair1, pair2: t.pair2, pair3: t.pair3,
                 minProfitUsd: t.minProfitUsd, minProfitPercent: t.minProfitPercent, status: t.status, cycle: t.cycle });
    setTriDialogOpen(true);
  };
  const handleSaveTri = async () => {
    if (editingTriId == null) await createTriangle(triForm); else await updateTriangle(editingTriId, triForm);
    setTriDialogOpen(false); void loadTriangles();
  };
  const handleDeleteTri    = async (id: number) => { await deleteTriangle(id); void loadTriangles(); };
  const handleToggleStatus = async (t: TriangleConfig) => {
    const next: TriangleStatus = t.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    await updateTriangle(t.id, { ...t, status: next }); void loadTriangles();
  };

  const triTextField = (key: keyof TrianglePayload, label: string, type = 'text') => (
    <TextField key={key} label={label} type={type} fullWidth size="small" value={triForm[key]}
      onChange={(e) => setTriForm(f => ({ ...f, [key]: type === 'number' ? Number(e.target.value) : e.target.value }))} />
  );

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>

      {/* ═══════════════════════════════════ EXCHANGE SETTINGS ══════════════════════════════════ */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1.5 }}>
        <Typography variant="h5">Exchange Settings</Typography>
        <Button variant="contained" size="small" onClick={openCreateEx}>Add Exchange</Button>
      </Box>

      <Paper sx={{ mb: 4 }}>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Exchange</TableCell>
                <TableCell>API Key</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>Order Size USD</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>Pos. Limit</TableCell>
                <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>Max Daily Loss</TableCell>
                <TableCell align="center">Enabled</TableCell>
                <TableCell align="center">ModOp</TableCell>
                <TableCell align="center">Scan</TableCell>
                <TableCell align="center">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {exchanges.map((c) => {
                const running = exchangeRunning[c.exchange] ?? false;
                return (
                  <TableRow key={c.id}>
                    <TableCell>
                      <Chip label={c.exchange} size="small"
                        color={c.enabled ? 'primary' : 'default'} variant={c.enabled ? 'filled' : 'outlined'} />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', color: 'text.secondary' }}>
                      {maskKey(c.apiKey)}
                    </TableCell>
                    <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                      ${c.orderSizeUsd.toLocaleString()}
                    </TableCell>
                    <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                      ${c.positionLimitUsd.toLocaleString()}
                    </TableCell>
                    <TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' }, color: 'error.main' }}>
                      ${c.maxDailyLossUsd.toLocaleString()}
                    </TableCell>
                    <TableCell align="center">
                      <Switch size="small" checked={c.enabled} onChange={() => void handleToggleEx(c)} />
                    </TableCell>
                    <TableCell align="center">
                      <Tooltip title={c.simulation ? 'Switch to Real Trade' : 'Switch to Simulation'}>
                        <Chip
                          label={c.simulation ? 'SIM' : 'REAL'}
                          size="small"
                          color={c.simulation ? 'default' : 'warning'}
                          variant={c.simulation ? 'outlined' : 'filled'}
                          onClick={() => void handleToggleModOp(c)}
                          sx={{ cursor: 'pointer', fontWeight: 600, minWidth: 52 }}
                        />
                      </Tooltip>
                    </TableCell>
                    <TableCell align="center">
                      {running
                        ? <Tooltip title="Stop scan"><IconButton size="small" color="error" onClick={() => void handleStopEx(c.exchange)}><StopIcon fontSize="small" /></IconButton></Tooltip>
                        : <Tooltip title="Start scan"><IconButton size="small" color="success" disabled={!c.enabled} onClick={() => void handleStartEx(c.exchange)}><PlayArrowIcon fontSize="small" /></IconButton></Tooltip>
                      }
                      <Chip label={running ? 'RUNNING' : 'IDLE'} size="small"
                        color={running ? 'success' : 'default'} variant="outlined"
                        sx={{ ml: 0.5, fontSize: '0.65rem' }} />
                    </TableCell>
                    <TableCell align="center">
                      <IconButton size="small" onClick={() => openEditEx(c)}><EditIcon fontSize="small" /></IconButton>
                      <IconButton size="small" color="error" onClick={() => void handleDeleteEx(c.id)}><DeleteIcon fontSize="small" /></IconButton>
                    </TableCell>
                  </TableRow>
                );
              })}
              {exchanges.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ py: 3, color: 'text.secondary' }}>
                    No exchanges configured
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      <Divider sx={{ my: 3 }} />

      {/* ═══════════════════════════════════ TRIANGLE CONFIGS ════════════════════════════════════ */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5">Triangle Configurations</Typography>
        <Button variant="contained" onClick={openCreateTri}>Add Triangle</Button>
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
                    <IconButton size="small" onClick={() => openEditTri(t)}><EditIcon fontSize="small" /></IconButton>
                    <IconButton size="small" color="error" onClick={() => void handleDeleteTri(t.id)}><DeleteIcon fontSize="small" /></IconButton>
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

      {/* ─── Add/Edit Exchange dialog ────────────────────────────────────────────── */}
      <Dialog open={exDialogOpen} onClose={() => setExDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editingExId == null ? 'Add Exchange' : 'Edit Exchange'}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            <FormControl fullWidth size="small">
              <InputLabel>Exchange</InputLabel>
              <Select label="Exchange" value={exForm.exchange}
                onChange={(e) => setExForm(f => ({ ...f, exchange: e.target.value }))}>
                {KNOWN_EXCHANGES.map(ex => <MenuItem key={ex} value={ex}>{ex}</MenuItem>)}
              </Select>
            </FormControl>
            <TextField label="API Key" size="small" fullWidth value={exForm.apiKey ?? ''}
              onChange={(e) => setExForm(f => ({ ...f, apiKey: e.target.value || null }))} />
            <TextField label="API Secret" size="small" fullWidth type="password" value={exForm.apiSecret ?? ''}
              onChange={(e) => setExForm(f => ({ ...f, apiSecret: e.target.value || null }))} />
            <TextField label="API Passphrase (Coinbase / KuCoin)" size="small" fullWidth value={exForm.apiPassphrase ?? ''}
              onChange={(e) => setExForm(f => ({ ...f, apiPassphrase: e.target.value || null }))} />
            <TextField label="WebSocket URL (leave blank for default)" size="small" fullWidth value={exForm.wsUrl ?? ''}
              onChange={(e) => setExForm(f => ({ ...f, wsUrl: e.target.value || null }))} />
            <TextField label="Order Size USD" size="small" fullWidth type="number" value={exForm.orderSizeUsd}
              onChange={(e) => setExForm(f => ({ ...f, orderSizeUsd: Number(e.target.value) }))} />
            <TextField label="Position Limit USD" size="small" fullWidth type="number" value={exForm.positionLimitUsd}
              onChange={(e) => setExForm(f => ({ ...f, positionLimitUsd: Number(e.target.value) }))} />
            <TextField label="Max Daily Loss USD (negative)" size="small" fullWidth type="number" value={exForm.maxDailyLossUsd}
              onChange={(e) => setExForm(f => ({ ...f, maxDailyLossUsd: Number(e.target.value) }))} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Switch size="small" checked={exForm.enabled}
                onChange={(e) => setExForm(f => ({ ...f, enabled: e.target.checked }))} />
              <Typography variant="body2">Enabled</Typography>
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setExDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => void handleSaveEx()}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* ─── Add/Edit Triangle dialog ─────────────────────────────────────────────── */}
      <Dialog open={triDialogOpen} onClose={() => setTriDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editingTriId == null ? 'Add Triangle' : 'Edit Triangle'}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            <FormControl fullWidth size="small">
              <InputLabel>Exchange</InputLabel>
              <Select label="Exchange" value={triForm.exchange}
                onChange={(e) => setTriForm(f => ({ ...f, exchange: e.target.value }))}>
                {KNOWN_EXCHANGES.map(ex => <MenuItem key={ex} value={ex}>{ex}</MenuItem>)}
              </Select>
            </FormControl>
            {triTextField('pair1', 'Pair 1 (e.g. EURUSD)')}
            {triTextField('pair2', 'Pair 2 (e.g. USDJPY)')}
            {triTextField('pair3', 'Pair 3 (e.g. EURJPY)')}
            {triTextField('minProfitPercent', 'Min Profit % (e.g. 0.00025)', 'number')}
            {triTextField('minProfitUsd', 'Min Profit USD', 'number')}
            <Box>
              <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block' }}>Cycle</Typography>
              <ToggleButtonGroup value={triForm.cycle} exclusive size="small" sx={{ flexWrap: 'wrap' }}
                onChange={(_, v) => { if (v) setTriForm(f => ({ ...f, cycle: v })); }}>
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
          <Button onClick={() => setTriDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => void handleSaveTri()}>Save</Button>
        </DialogActions>
      </Dialog>

      {/* ─── Manual trade dialog ─────────────────────────────────────────────────── */}
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
                      <TextField type="number" size="small" value={l.quantity.toFixed(4)}
                        onChange={(e) => updateLeg(l.legIndex, 'quantity', Number(e.target.value))}
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
