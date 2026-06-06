import { useEffect, useMemo, useState } from 'react';
import {
  Box, Container, FormControl, InputLabel, MenuItem, Paper,
  Select, Tooltip, Typography,
} from '@mui/material';
import { getTrades } from '../api/rest';
import type { Trade, TradeStatus } from '../types';

type DirectionFilter = Trade['direction'] | 'ALL';
type StatusFilter = TradeStatus | 'ALL';
type ExchangeFilter = string;

const HOURS = Array.from({ length: 24 }, (_, i) => i);
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
// Clock face emojis indexed by hour 0-23 (0 = 🕛 12:00, 1 = 🕐 1:00, …)
const CLOCK_EMOJIS = Array.from({ length: 24 }, (_, h) => {
  const h12 = h % 12;
  return h12 === 0 ? '\u{1F55B}' : String.fromCodePoint(0x1F54F + h12);
});

const TZ = 'America/Chicago';
const WEEKDAYS = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];

/** Java LocalDateTime has no 'Z' — append it so JS treats the value as UTC. */
function parseUTC(ts: string): Date {
  return new Date(ts.endsWith('Z') ? ts : ts + 'Z');
}

function chicagoHour(d: Date): number {
  const h = Number(new Intl.DateTimeFormat('en-US', {
    timeZone: TZ, hour: 'numeric', hour12: false,
  }).format(d));
  return h % 24; // midnight returns 24 in some engines
}

function chicagoDayIndex(d: Date): number {
  const wd = new Intl.DateTimeFormat('en-US', { timeZone: TZ, weekday: 'short' }).format(d);
  return WEEKDAYS.indexOf(wd); // 0=Mon … 6=Sun
}

function lerp(a: number, b: number, t: number) { return a + (b - a) * t; }

export default function Analytics() {
  const [trades, setTrades] = useState<Trade[]>([]);
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [direction, setDirection] = useState<DirectionFilter>('ALL');
  const [exchange, setExchange] = useState<ExchangeFilter>('ALL');

  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    void getTrades().then((r) => setTrades(r.data));
  }, []);

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(t);
  }, []);

  const currentDay  = chicagoDayIndex(now);
  const currentHour = chicagoHour(now);

  const exchanges = useMemo(() => {
    const s = new Set(trades.map((t) => t.exchange));
    return Array.from(s).sort();
  }, [trades]);

  const filtered = useMemo(() => trades.filter((t) => {
    if (status !== 'ALL' && t.status !== status) return false;
    if (direction !== 'ALL' && t.direction !== direction) return false;
    if (exchange !== 'ALL' && t.exchange !== exchange) return false;
    return true;
  }), [trades, status, direction, exchange]);

  const matrix = useMemo(() => {
    const m: number[][] = Array.from({ length: 7 }, () => new Array(24).fill(0));
    for (const t of filtered) {
      const d = parseUTC(t.time);
      m[chicagoDayIndex(d)][chicagoHour(d)]++;
    }
    return m;
  }, [filtered]);

  const pnlMatrix = useMemo(() => {
    const m: number[][] = Array.from({ length: 7 }, () => new Array(24).fill(0));
    for (const t of filtered) {
      const d = parseUTC(t.time);
      m[chicagoDayIndex(d)][chicagoHour(d)] += t.pnl;
    }
    return m;
  }, [filtered]);

  const maxCount = useMemo(() => Math.max(...matrix.flat(), 1), [matrix]);
  const maxPnl   = useMemo(() => Math.max(...pnlMatrix.flat(), 0.0001), [pnlMatrix]);

  const cellColor = (count: number): string => {
    if (count === 0) return 'rgba(255,255,255,0.05)';
    const t = count / maxCount;
    // Dim navy → vivid cyan
    const r = Math.round(lerp(30, 0, t));
    const g = Math.round(lerp(58, 229, t));
    const b = Math.round(lerp(95, 255, t));
    return `rgb(${r},${g},${b})`;
  };

  const totalPnl = useMemo(() => filtered.reduce((s, t) => s + t.pnl, 0), [filtered]);

  const pnlCellColor = (pnl: number): string => {
    if (pnl <= 0) return 'rgba(255,255,255,0.05)';
    const t = pnl / maxPnl;
    // Dark forest green → bright lime-green
    const r = Math.round(lerp(10, 0,   t));
    const g = Math.round(lerp(60, 220, t));
    const b = Math.round(lerp(25, 60,  t));
    return `rgb(${r},${g},${b})`;
  };

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 0.5 }}>Analytics</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Heatmaps by day of week and hour — opportunity frequency and cumulative profit. Times in US Central.
      </Typography>

      <Box sx={{ display: 'flex', gap: 2, mb: 3 }}>
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel>Status</InputLabel>
          <Select
            label="Status"
            value={status}
            onChange={(e) => setStatus(e.target.value as StatusFilter)}
          >
            <MenuItem value="ALL">All</MenuItem>
            <MenuItem value="FILLED">Filled</MenuItem>
            <MenuItem value="SIMULATION">Simulation</MenuItem>
            <MenuItem value="CANCELLED">Cancelled</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel>Direction</InputLabel>
          <Select
            label="Direction"
            value={direction}
            onChange={(e) => setDirection(e.target.value as DirectionFilter)}
          >
            <MenuItem value="ALL">All</MenuItem>
            <MenuItem value="BBS">BBS</MenuItem>
            <MenuItem value="BSS">BSS</MenuItem>
            <MenuItem value="BSB">BSB</MenuItem>
            <MenuItem value="SBS">SBS</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel>Exchange</InputLabel>
          <Select
            label="Exchange"
            value={exchange}
            onChange={(e) => setExchange(e.target.value as ExchangeFilter)}
          >
            <MenuItem value="ALL">All</MenuItem>
            {exchanges.map((ex) => (
              <MenuItem key={ex} value={ex}>{ex}</MenuItem>
            ))}
          </Select>
        </FormControl>
      </Box>

      <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>

        {/* ── Frequency heatmap ── */}
        <Paper sx={{ p: 3, overflowX: 'auto', flex: '0 0 auto' }}>
          <Typography variant="subtitle2" sx={{ mb: 2, color: 'text.secondary' }}>
            Opportunity Frequency Heatmap
            <Box component="span" sx={{ ml: 1, fontWeight: 400 }}>
              ({filtered.length.toLocaleString()} opportunities)
            </Box>
          </Typography>

          <Box sx={{ display: 'inline-flex', gap: 0.5 }}>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, pt: '28px' }}>
              {DAYS.map((day) => (
                <Box key={day} sx={{
                  width: 30, height: 24,
                  display: 'flex', alignItems: 'center', justifyContent: 'flex-end',
                  pr: 1, fontSize: '0.68rem', color: 'text.secondary',
                }}>
                  {day}
                </Box>
              ))}
            </Box>

            <Box>
              <Box sx={{ display: 'flex', gap: 0.5, mb: 0.5 }}>
                {HOURS.map((h) => (
                  <Box key={h} sx={{
                    width: 24, fontSize: '0.6rem', color: 'text.secondary',
                    textAlign: 'center', lineHeight: '20px',
                  }}>
                    {h % 6 === 0 ? h : ''}
                  </Box>
                ))}
              </Box>

              {DAYS.map((day, di) => (
                <Box key={day} sx={{ display: 'flex', gap: 0.5, mb: 0.5 }}>
                  {HOURS.map((h) => {
                    const count = matrix[di][h];
                    const isNow = di === currentDay && h === currentHour;
                    return (
                      <Tooltip
                        key={h}
                        title={`${day} ${String(h).padStart(2, '0')}:00–${String(h + 1).padStart(2, '0')}:00 · ${count} opportunit${count === 1 ? 'y' : 'ies'}`}
                        placement="top"
                        arrow
                      >
                        <Box sx={{
                          width: 24, height: 24, borderRadius: '3px',
                          bgcolor: cellColor(count),
                          cursor: 'default',
                          position: 'relative',
                          transition: 'transform 0.1s',
                          '&:hover': { transform: 'scale(1.3)', zIndex: 2 },
                        }}>
                          {isNow && (
                            <Box sx={{
                              position: 'absolute', inset: 0,
                              display: 'flex', alignItems: 'center', justifyContent: 'center',
                              fontSize: '0.7rem', lineHeight: 1, pointerEvents: 'none',
                            }}>
                              {CLOCK_EMOJIS[h]}
                            </Box>
                          )}
                        </Box>
                      </Tooltip>
                    );
                  })}
                </Box>
              ))}
            </Box>
          </Box>

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 2.5 }}>
            <Typography variant="caption" color="text.secondary">Less</Typography>
            {[0, 0.25, 0.5, 0.75, 1].map((v, i) => (
              <Box key={i} sx={{
                width: 18, height: 18, borderRadius: '3px',
                bgcolor: v === 0 ? 'rgba(255,255,255,0.05)' : cellColor(Math.round(v * maxCount)),
              }} />
            ))}
            <Typography variant="caption" color="text.secondary">More</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ ml: 2 }}>
              Peak: {maxCount} / hour slot
            </Typography>
          </Box>
        </Paper>

        {/* ── Profit heatmap ── */}
        <Paper sx={{ p: 3, overflowX: 'auto', flex: '0 0 auto' }}>
          <Typography variant="subtitle2" sx={{ mb: 2, color: 'text.secondary' }}>
            Profit Heatmap
            <Box component="span" sx={{ ml: 1, fontWeight: 400 }}>
              (total ${totalPnl.toFixed(2)})
            </Box>
          </Typography>

          <Box sx={{ display: 'inline-flex', gap: 0.5 }}>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, pt: '28px' }}>
              {DAYS.map((day) => (
                <Box key={day} sx={{
                  width: 30, height: 24,
                  display: 'flex', alignItems: 'center', justifyContent: 'flex-end',
                  pr: 1, fontSize: '0.68rem', color: 'text.secondary',
                }}>
                  {day}
                </Box>
              ))}
            </Box>

            <Box>
              <Box sx={{ display: 'flex', gap: 0.5, mb: 0.5 }}>
                {HOURS.map((h) => (
                  <Box key={h} sx={{
                    width: 24, fontSize: '0.6rem', color: 'text.secondary',
                    textAlign: 'center', lineHeight: '20px',
                  }}>
                    {h % 6 === 0 ? h : ''}
                  </Box>
                ))}
              </Box>

              {DAYS.map((day, di) => (
                <Box key={day} sx={{ display: 'flex', gap: 0.5, mb: 0.5 }}>
                  {HOURS.map((h) => {
                    const pnl = pnlMatrix[di][h];
                    const sign = pnl >= 0 ? '+' : '';
                    const isNow = di === currentDay && h === currentHour;
                    return (
                      <Tooltip
                        key={h}
                        title={`${day} ${String(h).padStart(2, '0')}:00–${String(h + 1).padStart(2, '0')}:00 · ${sign}$${pnl.toFixed(2)}`}
                        placement="top"
                        arrow
                      >
                        <Box sx={{
                          width: 24, height: 24, borderRadius: '3px',
                          bgcolor: pnlCellColor(pnl),
                          cursor: 'default',
                          position: 'relative',
                          transition: 'transform 0.1s',
                          '&:hover': { transform: 'scale(1.3)', zIndex: 2 },
                        }}>
                          {isNow && (
                            <Box sx={{
                              position: 'absolute', inset: 0,
                              display: 'flex', alignItems: 'center', justifyContent: 'center',
                              fontSize: '0.7rem', lineHeight: 1, pointerEvents: 'none',
                            }}>
                              {CLOCK_EMOJIS[h]}
                            </Box>
                          )}
                        </Box>
                      </Tooltip>
                    );
                  })}
                </Box>
              ))}
            </Box>
          </Box>

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 2.5 }}>
            <Typography variant="caption" color="text.secondary">Less</Typography>
            {[0, 0.25, 0.5, 0.75, 1].map((v, i) => (
              <Box key={i} sx={{
                width: 18, height: 18, borderRadius: '3px',
                bgcolor: v === 0 ? 'rgba(255,255,255,0.05)' : pnlCellColor(v * maxPnl),
              }} />
            ))}
            <Typography variant="caption" color="text.secondary">More</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ ml: 2 }}>
              Peak: ${maxPnl.toFixed(2)} / hour slot
            </Typography>
          </Box>
        </Paper>

      </Box>
    </Container>
  );
}
