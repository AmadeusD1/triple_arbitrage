import { useEffect, useMemo, useState } from 'react';
import {
  Box, Container, FormControl, InputLabel, MenuItem, Paper,
  Select, Tooltip, Typography,
} from '@mui/material';
import { getTrades } from '../api/rest';
import type { Trade, TradeStatus } from '../types';

type DirectionFilter = Trade['direction'] | 'ALL';
type StatusFilter = TradeStatus | 'ALL';

const HOURS = Array.from({ length: 24 }, (_, i) => i);
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function dayIndex(d: Date): number {
  return (d.getDay() + 6) % 7; // 0=Mon … 6=Sun
}

function lerp(a: number, b: number, t: number) { return a + (b - a) * t; }

export default function Analytics() {
  const [trades, setTrades] = useState<Trade[]>([]);
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [direction, setDirection] = useState<DirectionFilter>('ALL');

  useEffect(() => {
    void getTrades().then((r) => setTrades(r.data));
  }, []);

  const filtered = useMemo(() => trades.filter((t) => {
    if (status !== 'ALL' && t.status !== status) return false;
    if (direction !== 'ALL' && t.direction !== direction) return false;
    return true;
  }), [trades, status, direction]);

  const matrix = useMemo(() => {
    const m: number[][] = Array.from({ length: 7 }, () => new Array(24).fill(0));
    for (const t of filtered) {
      const d = new Date(t.time);
      m[dayIndex(d)][d.getHours()]++;
    }
    return m;
  }, [filtered]);

  const maxCount = useMemo(() => Math.max(...matrix.flat(), 1), [matrix]);

  const cellColor = (count: number): string => {
    if (count === 0) return 'rgba(255,255,255,0.05)';
    const t = count / maxCount;
    // Dim navy → vivid cyan
    const r = Math.round(lerp(30, 0, t));
    const g = Math.round(lerp(58, 229, t));
    const b = Math.round(lerp(95, 255, t));
    return `rgb(${r},${g},${b})`;
  };

  return (
    <Container maxWidth="xl" sx={{ py: 3 }}>
      <Typography variant="h5" sx={{ mb: 0.5 }}>Analytics</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Opportunity frequency heatmap — when arbitrage opportunities appear most often by hour and day of week.
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
      </Box>

      <Paper sx={{ p: 3, overflowX: 'auto' }}>
        <Typography variant="subtitle2" sx={{ mb: 2, color: 'text.secondary' }}>
          Opportunity Frequency Heatmap
          <Box component="span" sx={{ ml: 1, fontWeight: 400 }}>
            ({filtered.length.toLocaleString()} opportunities)
          </Box>
        </Typography>

        <Box sx={{ display: 'inline-flex', gap: 0.5 }}>
          {/* Day labels */}
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
            {/* Hour labels */}
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

            {/* Grid */}
            {DAYS.map((day, di) => (
              <Box key={day} sx={{ display: 'flex', gap: 0.5, mb: 0.5 }}>
                {HOURS.map((h) => {
                  const count = matrix[di][h];
                  return (
                    <Tooltip
                      key={h}
                      title={`${day} ${String(h).padStart(2, '0')}:00–${String(h + 1).padStart(2, '0')}:00 · ${count} opportunit${count === 1 ? 'y' : 'ies'}`}
                      placement="top"
                      arrow
                    >
                      <Box sx={{
                        width: 24,
                        height: 24,
                        borderRadius: '3px',
                        bgcolor: cellColor(count),
                        cursor: 'default',
                        transition: 'transform 0.1s',
                        '&:hover': { transform: 'scale(1.3)', zIndex: 2, position: 'relative' },
                      }} />
                    </Tooltip>
                  );
                })}
              </Box>
            ))}
          </Box>
        </Box>

        {/* Legend */}
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
    </Container>
  );
}
