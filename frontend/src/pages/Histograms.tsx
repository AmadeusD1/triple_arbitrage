import { useMemo, useState } from 'react';
import { useSessionState } from '../hooks/useSessionState';
import {
  Box, Button, FormControl, InputLabel, MenuItem, Paper, Select, Typography,
} from '@mui/material';
import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer,
  Tooltip as RechartsTooltip, XAxis, YAxis,
} from 'recharts';
import type { SelectChangeEvent } from '@mui/material';
import type { Trade } from '../types';

const TZ = 'America/Chicago';
const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

type TradeType = 'ALL' | 'REAL' | 'SIMULATION';
type GroupBy   = 'YEARS' | 'MONTHS' | 'DAYS';

interface Datum { label: string; pnl: number; }

function parseUTC(ts: string): Date {
  return new Date(ts.endsWith('Z') ? ts : ts + 'Z');
}

function dateParts(d: Date) {
  const fmt = (opts: Intl.DateTimeFormatOptions) =>
    Number(new Intl.DateTimeFormat('en-US', { timeZone: TZ, ...opts }).format(d));
  return {
    year:  fmt({ year: 'numeric' }),
    month: fmt({ month: 'numeric' }) - 1, // 0-indexed
    day:   fmt({ day: 'numeric' }),
  };
}

function daysInMonth(year: number, month: number) {
  return new Date(year, month + 1, 0).getDate();
}

function buildData(
  trades: Trade[], groupBy: GroupBy,
  year: number | null, month: number | null,
): Datum[] {
  if (groupBy === 'YEARS') {
    const map = new Map<number, number>();
    for (const t of trades) {
      const { year: y } = dateParts(parseUTC(t.time));
      map.set(y, (map.get(y) ?? 0) + t.pnl);
    }
    return [...map.entries()]
      .sort(([a], [b]) => a - b)
      .map(([y, pnl]) => ({ label: String(y), pnl }));
  }

  if (groupBy === 'MONTHS' && year !== null) {
    const map = new Map<number, number>();
    for (const t of trades) {
      const { year: y, month: m } = dateParts(parseUTC(t.time));
      if (y !== year) continue;
      map.set(m, (map.get(m) ?? 0) + t.pnl);
    }
    // Only show months that have data — no leading/trailing zero bars
    return [...map.entries()]
      .sort(([a], [b]) => a - b)
      .map(([m, pnl]) => ({ label: MONTH_LABELS[m], pnl }));
  }

  if (groupBy === 'DAYS' && year !== null && month !== null) {
    const map = new Map<number, number>();
    for (const t of trades) {
      const { year: y, month: mo, day } = dateParts(parseUTC(t.time));
      if (y !== year || mo !== month) continue;
      map.set(day, (map.get(day) ?? 0) + t.pnl);
    }
    // Show every day of the month (0 bars for days with no trades)
    const count = daysInMonth(year, month);
    return Array.from({ length: count }, (_, i) => ({
      label: String(i + 1),
      pnl: map.get(i + 1) ?? 0,
    }));
  }

  return [];
}

interface Props {
  trades: Trade[];
  exchanges: string[];
}

export default function Histograms({ trades, exchanges }: Props) {
  const [tradeType, setTradeType]         = useSessionState<TradeType>('histograms:tradeType', 'ALL');
  const [exchange, setExchange]           = useSessionState('histograms:exchange', 'ALL');
  const [groupBy, setGroupBy]             = useSessionState<GroupBy>('histograms:groupBy', 'YEARS');
  const [selectedYear, setSelectedYear]   = useSessionState<number | ''>('histograms:selectedYear', '');
  const [selectedMonth, setSelectedMonth] = useSessionState<number | ''>('histograms:selectedMonth', '');
  const [chartData, setChartData]         = useState<Datum[] | null>(null);

  // Only years that actually have trade data
  const availableYears = useMemo(() =>
    [...new Set(trades.map(t => dateParts(parseUTC(t.time)).year))].sort((a, b) => a - b),
    [trades],
  );

  // Only months (of the selected year) that actually have trade data
  const availableMonths = useMemo(() => {
    if (selectedYear === '') return [];
    const yr = selectedYear as number;
    const months = new Set(
      trades
        .filter(t => dateParts(parseUTC(t.time)).year === yr)
        .map(t => dateParts(parseUTC(t.time)).month),
    );
    return [...months].sort((a, b) => a - b);
  }, [trades, selectedYear]);

  const canRun =
    groupBy === 'YEARS' ||
    (groupBy === 'MONTHS' && selectedYear !== '') ||
    (groupBy === 'DAYS'   && selectedYear !== '' && selectedMonth !== '');

  function handleRun() {
    const filtered = trades.filter(t => {
      if (tradeType === 'REAL'       && t.status !== 'FILLED')     return false;
      if (tradeType === 'SIMULATION' && t.status !== 'SIMULATION') return false;
      if (exchange !== 'ALL'         && t.exchange !== exchange)    return false;
      return true;
    });
    setChartData(buildData(
      filtered, groupBy,
      selectedYear  !== '' ? (selectedYear  as number) : null,
      selectedMonth !== '' ? (selectedMonth as number) : null,
    ));
  }

  function onGroupByChange(e: SelectChangeEvent) {
    setGroupBy(e.target.value as GroupBy);
    setSelectedYear('');
    setSelectedMonth('');
    setChartData(null);
  }

  const totalPnl = chartData?.reduce((s, d) => s + d.pnl, 0) ?? null;
  const hasData  = chartData !== null && chartData.length > 0;

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 0.5 }}>Histograms</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Profit distribution by time period. Select filters and click Run.
      </Typography>

      {/* Filter row */}
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center', mb: 3 }}>
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel>Trade Type</InputLabel>
          <Select
            label="Trade Type" value={tradeType}
            onChange={e => setTradeType(e.target.value as TradeType)}
          >
            <MenuItem value="ALL">All</MenuItem>
            <MenuItem value="REAL">Real</MenuItem>
            <MenuItem value="SIMULATION">Simulation</MenuItem>
          </Select>
        </FormControl>

        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel>Exchange</InputLabel>
          <Select label="Exchange" value={exchange} onChange={e => setExchange(e.target.value)}>
            <MenuItem value="ALL">All</MenuItem>
            {exchanges.map(ex => <MenuItem key={ex} value={ex}>{ex}</MenuItem>)}
          </Select>
        </FormControl>

        <FormControl size="small" sx={{ minWidth: 130 }}>
          <InputLabel>Group By</InputLabel>
          <Select label="Group By" value={groupBy} onChange={onGroupByChange}>
            <MenuItem value="YEARS">Years</MenuItem>
            <MenuItem value="MONTHS">Months</MenuItem>
            <MenuItem value="DAYS">Days</MenuItem>
          </Select>
        </FormControl>

        {(groupBy === 'MONTHS' || groupBy === 'DAYS') && (
          <FormControl size="small" sx={{ minWidth: 110 }}>
            <InputLabel>Year</InputLabel>
            <Select
              label="Year" value={selectedYear}
              onChange={e => {
                setSelectedYear(e.target.value as number | '');
                setSelectedMonth('');
                setChartData(null);
              }}
            >
              {availableYears.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
            </Select>
          </FormControl>
        )}

        {groupBy === 'DAYS' && (
          <FormControl size="small" sx={{ minWidth: 120 }}>
            <InputLabel>Month</InputLabel>
            <Select
              label="Month" value={selectedMonth}
              onChange={e => {
                setSelectedMonth(e.target.value as number | '');
                setChartData(null);
              }}
            >
              {availableMonths.map(m => (
                <MenuItem key={m} value={m}>{MONTH_LABELS[m]}</MenuItem>
              ))}
            </Select>
          </FormControl>
        )}

        <Button
          variant="contained" disabled={!canRun}
          onClick={handleRun} sx={{ minWidth: 80 }}
        >
          Run
        </Button>
      </Box>

      {/* Chart */}
      <Paper sx={{ p: 3, position: 'relative' }}>
        {hasData && (
          <Box sx={{ display: 'flex', gap: 3, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">
              {chartData!.length} bucket{chartData!.length !== 1 ? 's' : ''}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Total: {totalPnl! >= 0 ? '+' : ''}${totalPnl!.toFixed(4)}
            </Typography>
          </Box>
        )}

        <ResponsiveContainer width="100%" height={340}>
          <BarChart data={chartData ?? []} margin={{ top: 8, right: 24, bottom: 8, left: 24 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" />
            <XAxis dataKey="label" tick={{ fontSize: 12, fill: '#aaa' }} />
            <YAxis
              tickFormatter={(v: number) => `$${v.toFixed(2)}`}
              tick={{ fontSize: 11, fill: '#aaa' }}
            />
            {hasData && (
              <>
                <RechartsTooltip
                  cursor={false}
                  formatter={(v: unknown) => [`$${(v as number).toFixed(4)}`, 'Profit']}
                  contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
                  labelStyle={{ color: '#ccc' }}
                  itemStyle={{ color: '#fff' }}
                />
                <Bar dataKey="pnl" radius={[3, 3, 0, 0]} activeBar={false}>
                  {chartData!.map((d, i) => (
                    <Cell
                      key={i}
                      fill={d.pnl >= 0 ? '#4caf50' : '#f44336'}
                      fillOpacity={d.pnl === 0 ? 0.15 : 0.85}
                    />
                  ))}
                </Bar>
              </>
            )}
          </BarChart>
        </ResponsiveContainer>

        {!hasData && (
          <Box sx={{
            position: 'absolute', inset: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            pointerEvents: 'none',
          }}>
            <Typography variant="body2" color="text.secondary">
              {chartData === null
                ? 'Select options above and click Run to generate the histogram.'
                : 'No trades match the selected filters.'}
            </Typography>
          </Box>
        )}
      </Paper>
    </Box>
  );
}
