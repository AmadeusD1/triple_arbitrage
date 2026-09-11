import { useState, useEffect, useRef, useMemo } from 'react';
import {
    Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
    Paper, Typography, Box, Chip, keyframes
} from '@mui/material';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import TrendingDownIcon from '@mui/icons-material/TrendingDown';
import TrendingFlatIcon from '@mui/icons-material/TrendingFlat';

const flashGreen = keyframes`
  0%   { background-color: rgba(0, 200, 83, 0.35); }
  100% { background-color: transparent; }
`;
const flashRed = keyframes`
  0%   { background-color: rgba(244, 67, 54, 0.35); }
  100% { background-color: transparent; }
`;

export interface PriceEntry {
    price: number;
    calculated: boolean;
}

interface MarketFeedTableProps {
    prices: Map<string, PriceEntry>;
    onRowClick?: (pair: string) => void;
}

export function MarketFeedTable({ prices, onRowClick }: MarketFeedTableProps) {
    const prevPricesRef = useRef<Map<string, number>>(new Map());
    const [flashState, setFlashState] = useState<Map<string, 'up' | 'down'>>(new Map());

    useEffect(() => {
        const newFlash = new Map<string, 'up' | 'down'>();
        prices.forEach((entry, pair) => {
            const prev = prevPricesRef.current.get(pair);
            if (prev !== undefined && prev !== entry.price) {
                newFlash.set(pair, entry.price > prev ? 'up' : 'down');
            }
        });
        if (newFlash.size > 0) {
            setFlashState(newFlash);
            const timer = setTimeout(() => setFlashState(new Map()), 600);
            prevPricesRef.current = new Map(Array.from(prices.entries()).map(([k, v]) => [k, v.price]));
            return () => clearTimeout(timer);
        }
        prevPricesRef.current = new Map(Array.from(prices.entries()).map(([k, v]) => [k, v.price]));
    }, [prices]);

    const sorted = useMemo(() =>
        Array.from(prices.entries()).sort(([a], [b]) => a.localeCompare(b)),
        [prices]
    );

    if (sorted.length === 0) {
        return (
            <Box sx={{ p: 4, textAlign: 'center' }}>
                <Typography sx={{ color: 'text.secondary' }}>Connecting to aggregator…</Typography>
            </Box>
        );
    }

    return (
        <TableContainer component={Paper}>
            <Table size="small">
                <TableHead>
                    <TableRow>
                        <TableCell>Pair</TableCell>
                        <TableCell align="right">Mid Price (USD)</TableCell>
                        <TableCell align="center">Trend</TableCell>
                    </TableRow>
                </TableHead>
                <TableBody>
                    {sorted.map(([pair, entry]) => {
                        const { price, calculated } = entry;
                        const flash = flashState.get(pair);
                        return (
                            <TableRow
                                key={pair}
                                onClick={() => onRowClick?.(pair)}
                                sx={{
                                    cursor: onRowClick ? 'pointer' : 'default',
                                    animation: flash === 'up'
                                        ? `${flashGreen} 0.6s ease-out`
                                        : flash === 'down'
                                            ? `${flashRed} 0.6s ease-out`
                                            : 'none',
                                    '&:hover': { bgcolor: 'action.hover' },
                                }}
                            >
                                <TableCell sx={{ fontWeight: 600 }}>
                                    {pair}
                                    {calculated && (
                                        <Chip
                                            label="calc"
                                            size="small"
                                            sx={{
                                                ml: 1, height: '16px', fontSize: '0.6rem',
                                                bgcolor: 'transparent', color: 'primary.main',
                                                border: '1px solid', borderColor: 'primary.main',
                                            }}
                                        />
                                    )}
                                </TableCell>
                                <TableCell align="right" sx={{ fontFamily: 'monospace' }}>
                                    {price.toFixed(8)}
                                </TableCell>
                                <TableCell align="center">
                                    {flash === 'up'
                                        ? <TrendingUpIcon sx={{ color: '#00c853', fontSize: 18 }} />
                                        : flash === 'down'
                                            ? <TrendingDownIcon sx={{ color: '#f44336', fontSize: 18 }} />
                                            : <TrendingFlatIcon sx={{ color: 'text.disabled', fontSize: 18 }} />}
                                </TableCell>
                            </TableRow>
                        );
                    })}
                </TableBody>
            </Table>
        </TableContainer>
    );
}
