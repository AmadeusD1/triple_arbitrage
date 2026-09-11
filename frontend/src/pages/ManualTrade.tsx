import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControl, InputLabel, MenuItem, Select, TextField, Tooltip, Typography,
  ToggleButton, ToggleButtonGroup,
} from '@mui/material';
import { getExchangeConfigs, getManualTradePairs, getManualTradePrecision, submitManualOrder } from '../api/rest';
import type { ExchangeConfig, ManualOrderResponse } from '../types';

type OrderType = 'LIMIT' | 'MARKET';
type Side = 'BID' | 'ASK';

function clampDecimals(raw: string, decimals: number): string {
  let v = raw.replace(/[^0-9.]/g, '');
  const dot = v.indexOf('.');
  if (dot !== -1) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '');
  if (decimals === 0) return v.split('.')[0];
  const [intPart, decPart] = v.split('.');
  return decPart === undefined ? v : `${intPart}.${decPart.slice(0, decimals)}`;
}

export default function ManualTrade() {
  const [exchanges, setExchanges] = useState<ExchangeConfig[]>([]);
  const [exchange, setExchange] = useState('');
  const [pairs, setPairs] = useState<string[]>([]);
  const [pair, setPair] = useState('');
  const [orderType, setOrderType] = useState<OrderType>('LIMIT');
  const [side, setSide] = useState<Side>('BID');
  const [quantity, setQuantity] = useState('');
  const [limitPrice, setLimitPrice] = useState('');
  const [precision, setPrecision] = useState({ priceDecimals: 8, qtyDecimals: 8 });
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<{ open: boolean; success: boolean; message: string }>(
    { open: false, success: false, message: '' },
  );

  useEffect(() => {
    void getExchangeConfigs().then(r =>
      setExchanges(r.data.filter(c => c.enabled).sort((a, b) => a.exchange.localeCompare(b.exchange))));
  }, []);

  useEffect(() => {
    setPair('');
    setPairs([]);
    if (!exchange) return;
    void getManualTradePairs(exchange).then(r => setPairs(r.data));
  }, [exchange]);

  useEffect(() => {
    if (!exchange || !pair) { setPrecision({ priceDecimals: 8, qtyDecimals: 8 }); return; }
    void getManualTradePrecision(exchange, pair).then(r => setPrecision(r.data));
  }, [exchange, pair]);

  const htxMarketBlocksBid = exchange === 'HTX' && orderType === 'MARKET';

  useEffect(() => {
    if (htxMarketBlocksBid && side === 'BID') setSide('ASK');
  }, [htxMarketBlocksBid, side]);

  useEffect(() => {
    if (orderType === 'MARKET') setLimitPrice('');
  }, [orderType]);

  const close = () => { window.location.href = '/'; };

  const reset = () => {
    setExchange('');
    setOrderType('LIMIT');
    setSide('BID');
    setQuantity('');
    setLimitPrice('');
  };

  const selectedCfg = exchanges.find(e => e.exchange === exchange);
  const qtyNum = Number(quantity);
  const priceNum = Number(limitPrice);
  const validCombo = !(exchange === 'HTX' && orderType === 'MARKET' && side === 'BID');
  const canSubmit = exchange !== '' && pair !== '' && qtyNum > 0
    && (orderType === 'MARKET' || priceNum > 0)
    && selectedCfg != null && !selectedCfg.simulation
    && validCombo;

  const direction = side === 'BID' ? 'BUY' : 'SELL';
  const orderSummary = orderType === 'MARKET'
    ? `${direction} ${quantity} ${pair} at MARKET price on ${exchange}`
    : `${direction} ${quantity} ${pair} @ ${limitPrice} on ${exchange}`;

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const res = await submitManualOrder({
        exchange, pair, orderType, side, quantity: qtyNum,
        limitPrice: orderType === 'MARKET' ? null : priceNum,
      });
      const data: ManualOrderResponse = res.data;
      if (data.success) {
        setResult({
          open: true, success: true,
          message: `Order placed: ${data.direction} ${data.quantity} ${data.pair} on ${exchange}`
            + (data.price ? ` @ ${data.price}` : '') + (data.orderId ? ` (orderId ${data.orderId})` : ''),
        });
        reset();
      } else {
        setResult({ open: true, success: false, message: data.rejectionReason || 'Order rejected' });
      }
    } catch {
      setResult({ open: true, success: false, message: 'Order request failed — could not reach the server.' });
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  };

  return (
    <>
      <Dialog open onClose={close} maxWidth="sm" fullWidth>
        <DialogTitle>Generate Manual Order</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            <Alert severity="warning" sx={{ fontSize: '0.8rem' }}>
              This places a real order on the exchange. It is not a simulation.
            </Alert>

            <FormControl fullWidth size="small">
              <InputLabel>Exchange</InputLabel>
              <Select label="Exchange" value={exchange} onChange={(e) => setExchange(e.target.value)}>
                {exchanges.map(c => (
                  <MenuItem key={c.exchange} value={c.exchange} disabled={c.simulation}>
                    {c.simulation ? `${c.exchange} (simulation — disable in Exchange Settings)` : c.exchange}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControl fullWidth size="small" disabled={!exchange || pairs.length === 0}>
              <InputLabel>Currency Pair</InputLabel>
              <Select label="Currency Pair" value={pair} onChange={(e) => setPair(e.target.value)}>
                {pairs.map(p => <MenuItem key={p} value={p}>{p}</MenuItem>)}
              </Select>
            </FormControl>

            <Box>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>Order Type</Typography>
              <ToggleButtonGroup value={orderType} exclusive size="small" fullWidth
                onChange={(_, v: OrderType | null) => { if (v) setOrderType(v); }}>
                <ToggleButton value="LIMIT" sx={{ flex: 1 }}>Limit</ToggleButton>
                <ToggleButton value="MARKET" sx={{ flex: 1 }}>Market</ToggleButton>
              </ToggleButtonGroup>
            </Box>

            <Box>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>Side</Typography>
              <ToggleButtonGroup value={side} exclusive size="small" fullWidth
                onChange={(_, v: Side | null) => { if (v) setSide(v); }}>
                <Tooltip title={htxMarketBlocksBid
                  ? 'HTX market buy orders use quote-currency cost as the amount field, not base quantity — use a Limit order instead.'
                  : ''}>
                  <span style={{ flex: 1, display: 'inline-flex' }}>
                    <ToggleButton value="BID" disabled={htxMarketBlocksBid} sx={{ width: '100%' }}>
                      Bid (Buy)
                    </ToggleButton>
                  </span>
                </Tooltip>
                <ToggleButton value="ASK" sx={{ flex: 1 }}>Ask (Sell)</ToggleButton>
              </ToggleButtonGroup>
            </Box>

            <TextField
              label={`Quantity (max ${precision.qtyDecimals} decimals)`}
              size="small" fullWidth value={quantity}
              onChange={(e) => setQuantity(clampDecimals(e.target.value, precision.qtyDecimals))}
            />

            <TextField
              label={`Limit Price (max ${precision.priceDecimals} decimals)`}
              size="small" fullWidth value={limitPrice} disabled={orderType === 'MARKET'}
              onChange={(e) => setLimitPrice(clampDecimals(e.target.value, precision.priceDecimals))}
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={reset}>Reset</Button>
          <Box sx={{ flex: 1 }} />
          <Button onClick={close}>Close</Button>
          <Button variant="contained" color="warning" disabled={!canSubmit || submitting}
            onClick={() => setConfirmOpen(true)}>
            Submit
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Confirm Real Order</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 1 }}>This is a real order, not a simulation.</Alert>
          <Typography>{orderSummary}?</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmOpen(false)}>Cancel</Button>
          <Button variant="contained" color="warning" disabled={submitting} onClick={() => void handleSubmit()}>
            Confirm
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={result.open} onClose={() => setResult(r => ({ ...r, open: false }))} maxWidth="xs" fullWidth>
        <DialogTitle>{result.success ? 'Order Placed' : 'Order Rejected'}</DialogTitle>
        <DialogContent>
          <Alert severity={result.success ? 'success' : 'error'}>{result.message}</Alert>
        </DialogContent>
        <DialogActions>
          <Button variant="contained" onClick={() => setResult(r => ({ ...r, open: false }))}>Close</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
