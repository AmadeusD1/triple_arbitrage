import { useState, useEffect, useCallback } from 'react';
import {
    Container, Box, Typography, IconButton, Tooltip, CircularProgress
} from '@mui/material';
import SettingsIcon from '@mui/icons-material/Settings';
import { MarketFeedTable } from '../components/ccy/MarketFeedTable';
import { SettingsDrawer, type CcyExchange } from '../components/ccy/SettingsDrawer';
import { DebugDialog } from '../components/ccy/DebugDialog';

export default function CcyRates() {
    const [prices, setPrices] = useState<Map<string, { price: number; calculated: boolean }>>(new Map());
    const [wsStatus, setWsStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [exchanges, setExchanges] = useState<CcyExchange[]>([]);
    const [selectedPair, setSelectedPair] = useState<string | null>(null);

    useEffect(() => {
        const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
        const url = `${protocol}://${window.location.host}/ws/ccy/global`;
        let socket: WebSocket;
        let retryTimer: ReturnType<typeof setTimeout>;

        const connect = () => {
            socket = new WebSocket(url);
            socket.onopen = () => setWsStatus('connected');
            socket.onmessage = (event) => {
                try {
                    const data: Record<string, { price: number; calculated: boolean }> = JSON.parse(event.data);
                    setPrices(new Map(Object.entries(data)));
                } catch { /* ignore */ }
            };
            socket.onclose = () => {
                setWsStatus('disconnected');
                retryTimer = setTimeout(connect, 3000);
            };
            socket.onerror = () => socket.close();
        };

        connect();
        return () => { clearTimeout(retryTimer); socket?.close(); };
    }, []);

    const loadExchanges = useCallback(() => {
        fetch('/api/ccy/settings/exchanges')
            .then((r) => r.json())
            .then((data: CcyExchange[]) => setExchanges(data))
            .catch(() => {});
    }, []);

    const handleOpenSettings = () => {
        loadExchanges();
        setSettingsOpen(true);
    };

    const toggleExchange = async (id: number) => {
        await fetch(`/api/ccy/settings/exchanges/${id}/toggle`, { method: 'POST' });
        loadExchanges();
    };

    const toggleMarket = async (id: number) => {
        await fetch(`/api/ccy/settings/markets/${id}/toggle`, { method: 'POST' });
        loadExchanges();
    };

    const statusColor = wsStatus === 'connected' ? '#00c853' : wsStatus === 'connecting' ? '#ffab00' : '#f44336';
    const statusLabel = wsStatus === 'connected' ? 'Live' : wsStatus === 'connecting' ? 'Connecting…' : 'Disconnected';

    return (
        <Container maxWidth="md" sx={{ py: 3 }}>
            <Box display="flex" alignItems="center" justifyContent="space-between" mb={2}>
                <Box display="flex" alignItems="center" gap={1.5}>
                    <Typography variant="h5">CCY Rates</Typography>
                    <Box display="flex" alignItems="center" gap={0.5}>
                        {wsStatus === 'connecting'
                            ? <CircularProgress size={12} sx={{ color: statusColor }} />
                            : <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: statusColor }} />}
                        <Typography variant="caption" sx={{ color: statusColor }}>{statusLabel}</Typography>
                    </Box>
                </Box>
                <Tooltip title="Data sources">
                    <IconButton onClick={handleOpenSettings} size="small">
                        <SettingsIcon />
                    </IconButton>
                </Tooltip>
            </Box>

            <MarketFeedTable prices={prices} onRowClick={setSelectedPair} />

            <SettingsDrawer
                open={settingsOpen}
                onClose={() => setSettingsOpen(false)}
                exchanges={exchanges}
                onToggleExchange={toggleExchange}
                onToggleMarket={toggleMarket}
            />

            <DebugDialog pair={selectedPair} onClose={() => setSelectedPair(null)} />
        </Container>
    );
}
