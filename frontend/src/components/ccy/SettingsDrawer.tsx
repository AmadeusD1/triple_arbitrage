import {
    Drawer, Box, Typography, Accordion, AccordionSummary,
    AccordionDetails, List, ListItem, ListItemText, Switch, IconButton
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SettingsInputComponentIcon from '@mui/icons-material/SettingsInputComponent';
import { useState } from 'react';

export interface CcyExchange {
    id: number;
    name: string;
    enabled: boolean;
    markets: { id: number; coinPair: string; enabled: boolean }[];
}

interface SettingsDrawerProps {
    open: boolean;
    onClose: () => void;
    exchanges: CcyExchange[];
    onToggleExchange: (id: number) => void;
    onToggleMarket: (id: number) => void;
}

export function SettingsDrawer({ open, onClose, exchanges, onToggleExchange, onToggleMarket }: SettingsDrawerProps) {
    const [expanded, setExpanded] = useState<Record<number, boolean>>({});

    return (
        <Drawer anchor="right" open={open} onClose={onClose}>
            <Box sx={{ width: 380, p: 3, bgcolor: '#121212', height: '100%', color: '#fff' }}>
                <Box display="flex" alignItems="center" mb={3}>
                    <SettingsInputComponentIcon sx={{ color: '#90caf9', mr: 1 }} />
                    <Typography variant="h5" sx={{ fontWeight: 'bold', color: '#90caf9' }}>Data Sources</Typography>
                </Box>

                {exchanges.map((ex) => (
                    <Accordion
                        key={ex.id}
                        expanded={!!expanded[ex.id]}
                        sx={{ bgcolor: '#1e1e1e', color: '#fff', mb: 1, border: '1px solid #333', '&.Mui-expanded': { marginY: '8px' } }}
                    >
                        <AccordionSummary
                            onClick={() => onToggleExchange(ex.id)}
                            sx={{ cursor: 'pointer', '& .MuiAccordionSummary-content': { alignItems: 'center' } }}
                            expandIcon={
                                <IconButton
                                    size="small"
                                    onClick={(e) => { e.stopPropagation(); setExpanded(prev => ({ ...prev, [ex.id]: !prev[ex.id] })); }}
                                    sx={{ color: '#90caf9' }}
                                >
                                    <ExpandMoreIcon />
                                </IconButton>
                            }
                        >
                            <Box display="flex" alignItems="center" justifyContent="space-between" width="100%" pr={1}>
                                <Typography sx={{ fontWeight: 600, color: ex.enabled ? '#fff' : '#666' }}>{ex.name}</Typography>
                                <Switch
                                    size="small"
                                    checked={ex.enabled}
                                    onChange={(e) => { e.stopPropagation(); onToggleExchange(ex.id); }}
                                />
                            </Box>
                        </AccordionSummary>
                        <AccordionDetails sx={{ p: 0, bgcolor: '#181818' }}>
                            <List dense>
                                {ex.markets?.length > 0 ? ex.markets.map((m) => (
                                    <ListItem key={m.id} sx={{ pl: 4, borderBottom: '1px solid #222' }}>
                                        <ListItemText
                                            primary={m.coinPair}
                                            primaryTypographyProps={{ fontSize: '0.9rem', color: ex.enabled ? '#fff' : '#555', fontWeight: 500 }}
                                        />
                                        <Switch
                                            size="small"
                                            disabled={!ex.enabled}
                                            checked={m.enabled}
                                            onChange={() => onToggleMarket(m.id)}
                                            color="secondary"
                                        />
                                    </ListItem>
                                )) : (
                                    <Typography variant="caption" sx={{ p: 2, display: 'block', color: '#666', textAlign: 'center' }}>
                                        No markets found.
                                    </Typography>
                                )}
                            </List>
                        </AccordionDetails>
                    </Accordion>
                ))}
            </Box>
        </Drawer>
    );
}
