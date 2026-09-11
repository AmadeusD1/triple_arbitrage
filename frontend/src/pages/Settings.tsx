import { useEffect, useState } from 'react';
import {
  Box, Button, Container, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, Switch, TextField, Typography,
} from '@mui/material';
import { applySnapshotStaleMs, getSettings, putSettings } from '../api/rest';

interface SettingsState {
  position_limit: string;
  max_daily_loss: string;
  simulation_mode: number;
  snapshot_stale_ms: string;
}

export default function Settings() {
  const [values, setValues] = useState<SettingsState>({
    position_limit: '',
    max_daily_loss: '',
    simulation_mode: 1,
    snapshot_stale_ms: '60000',
  });
  const [applyConfirmOpen, setApplyConfirmOpen] = useState(false);

  useEffect(() => {
    getSettings().then((res) => {
      const map = Object.fromEntries(res.data.map((s) => [s.key, s.value]));
      setValues({
        position_limit: String(map['position_limit'] ?? ''),
        max_daily_loss: String(map['max_daily_loss'] ?? ''),
        simulation_mode: (map['simulation_mode'] as number) ?? 1,
        snapshot_stale_ms: String(map['snapshot_stale_ms'] ?? '60000'),
      });
    });
  }, []);

  const save = () =>
    putSettings({
      position_limit: Number(values.position_limit),
      max_daily_loss: Number(values.max_daily_loss),
      simulation_mode: values.simulation_mode,
      snapshot_stale_ms: Number(values.snapshot_stale_ms),
    });

  const handleApplyStaleMs = async () => {
    setApplyConfirmOpen(false);
    await applySnapshotStaleMs(Number(values.snapshot_stale_ms));
    await save();
  };

  return (
    <Container maxWidth="sm" sx={{ py: 4 }}>
      <Typography variant="h5" gutterBottom>Risk Settings</Typography>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label="Position Limit"
          value={values.position_limit}
          onChange={(e) => setValues((v) => ({ ...v, position_limit: e.target.value }))}
        />
        <TextField
          label="Max Daily Loss"
          value={values.max_daily_loss}
          onChange={(e) => setValues((v) => ({ ...v, max_daily_loss: e.target.value }))}
        />
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
          <TextField
            label="Snapshot Stale Threshold (ms)"
            value={values.snapshot_stale_ms}
            onChange={(e) => setValues((v) => ({ ...v, snapshot_stale_ms: e.target.value }))}
            helperText="Set a value then click APPLY to override all pair thresholds across every triangle"
            sx={{ flex: 1 }}
          />
          <Button
            variant="outlined"
            onClick={() => setApplyConfirmOpen(true)}
            sx={{ mt: '4px', height: 56, whiteSpace: 'nowrap' }}
          >
            APPLY
          </Button>
        </Box>
        <FormControlLabel
          label="Auto-start Scanner on Startup"
          control={
            <Switch
              checked={values.simulation_mode === 1}
              onChange={(e) =>
                setValues((v) => ({ ...v, simulation_mode: e.target.checked ? 1 : 0 }))
              }
            />
          }
        />
        <Button variant="contained" onClick={save}>Save</Button>
      </Box>

      <Dialog open={applyConfirmOpen} onClose={() => setApplyConfirmOpen(false)}>
        <DialogTitle>Override All Pair Thresholds?</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to override <strong>all</strong> pair snapshot stale thresholds
            to <strong>{values.snapshot_stale_ms} ms</strong>?
          </Typography>
          <Typography sx={{ mt: 1, color: 'text.secondary', fontSize: '0.875rem' }}>
            This will immediately replace the individual stale thresholds set on every triangle
            across all exchanges. Per-pair values you configured in Exchange Settings will be lost.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setApplyConfirmOpen(false)}>Cancel</Button>
          <Button variant="contained" color="warning" onClick={() => void handleApplyStaleMs()}>
            Yes, Override All
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}
