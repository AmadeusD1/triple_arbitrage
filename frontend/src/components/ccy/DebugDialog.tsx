import { useEffect, useState } from 'react';
import {
    Dialog, DialogTitle, DialogContent, List, ListItem,
    ListItemText, Divider, Typography, Box, Chip
} from '@mui/material';
import RadioButtonCheckedIcon from '@mui/icons-material/RadioButtonChecked';

interface MidPrice {
    pair: string;
    mid: number;
    timestamp: number;
    exchange: string;
}

interface DebugPairResponse {
    raw: Record<string, Record<string, MidPrice>>;
    usdPair: string;
    usd: number | null;
}

interface DebugDialogProps {
    pair: string | null;
    onClose: () => void;
}

export function DebugDialog({ pair, onClose }: DebugDialogProps) {
    const [breakdown, setBreakdown] = useState<DebugPairResponse | null>(null);
    const [isConnected, setIsConnected] = useState(false);

    useEffect(() => {
        if (!pair) { setBreakdown(null); setIsConnected(false); return; }

        const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
        const socket = new WebSocket(`${protocol}://${window.location.host}/ws/ccy/debug?pair=${encodeURIComponent(pair)}`);

        socket.onopen = () => setIsConnected(true);
        socket.onmessage = (event) => {
            try { setBreakdown(JSON.parse(event.data)); } catch { /* ignore */ }
        };
        socket.onclose = () => setIsConnected(false);
        socket.onerror = () => setIsConnected(false);

        return () => socket.close();
    }, [pair]);

    return (
        <Dialog
            open={!!pair}
            onClose={onClose}
            PaperProps={{ sx: { bgcolor: '#1e1e1e', color: '#fff', borderRadius: 3, border: '1px solid #444', backgroundImage: 'none' } }}
            maxWidth="xs"
            fullWidth
        >
            <DialogTitle sx={{ borderBottom: '1px solid #333', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Typography variant="h6" sx={{ color: '#90caf9', fontWeight: 'bold' }}>
                    Sources: {pair}
                </Typography>
                <Chip
                    icon={<RadioButtonCheckedIcon sx={{ fontSize: '12px !important', color: isConnected ? '#00e676' : '#ff5252' }} />}
                    label={isConnected ? 'LIVE' : 'OFFLINE'}
                    size="small"
                    sx={{
                        bgcolor: 'transparent',
                        color: isConnected ? '#00e676' : '#ff5252',
                        border: `1px solid ${isConnected ? '#00e676' : '#ff5252'}`,
                        fontSize: '0.7rem', fontWeight: 'bold'
                    }}
                />
            </DialogTitle>
            {breakdown && (
                <Box sx={{ px: 2, py: 1.5, borderBottom: '1px solid #333' }}>
                    <Typography sx={{ color: '#888', fontSize: '0.8rem' }}>
                        Calculated {breakdown.usdPair}
                    </Typography>
                    <Typography sx={{ color: '#00e676', fontWeight: 'bold', fontFamily: 'monospace' }}>
                        {breakdown.usd != null
                            ? `$${breakdown.usd.toFixed(8)}`
                            : 'Calculating…'}
                    </Typography>
                </Box>
            )}

            <DialogContent sx={{ p: 0 }}>
                <List>
                    {!breakdown ? (
                        <Box sx={{ p: 4, textAlign: 'center' }}>
                            <Typography sx={{ color: '#666' }}>Waiting for exchange data…</Typography>
                        </Box>
                    ) : Object.values(breakdown.raw).every(exchanges => Object.keys(exchanges).length === 0) ? (
                        <Box sx={{ p: 4, textAlign: 'center' }}>
                            <Typography sx={{ color: '#666' }}>No active data for this pair.</Typography>
                        </Box>
                    ) : (
                        Object.entries(breakdown.raw).map(([quotePair, exchanges]) =>
                            Object.keys(exchanges).length === 0 ? null : (
                                <Box key={quotePair}>
                                    <Typography sx={{ px: 2, pt: 1.5, pb: 0.5, color: '#90caf9', fontSize: '0.7rem', fontWeight: 'bold', letterSpacing: '0.05em' }}>
                                        {quotePair}
                                    </Typography>
                                    {Object.entries(exchanges).map(([exchange, data]) => {
                                        // data.timestamp is a unix timestamp in seconds (per MidPrice), not milliseconds.
                                        const ageSeconds = Math.floor(Date.now() / 1000 - data.timestamp);
                                        return (
                                            <Box key={`${quotePair}-${exchange}`}>
                                                <ListItem sx={{ py: 1.5 }}>
                                                    <ListItemText
                                                        primary={exchange}
                                                        secondary={`$${data.mid.toFixed(8)} | ${ageSeconds}s ago`}
                                                        slotProps={{
                                                            primary: { sx: { fontWeight: 'bold', color: '#fff', fontSize: '1rem' } },
                                                            secondary: { sx: { color: ageSeconds > 15 ? '#ff5252' : '#888', fontFamily: 'monospace', fontSize: '0.85rem' } }
                                                        }}
                                                    />
                                                </ListItem>
                                                <Divider sx={{ bgcolor: '#333', mx: 2 }} />
                                            </Box>
                                        );
                                    })}
                                </Box>
                            )
                        )
                    )}
                </List>
            </DialogContent>
        </Dialog>
    );
}
