import { useEffect, useState } from 'react';
import { Box, Button, Container, FormControlLabel, Switch, TextField, Typography } from '@mui/material';
import { getSettings, putSettings } from '../api/rest';

interface SettingsState {
  position_limit: string;
  max_daily_loss: string;
  simulation_mode: number;
}

export default function Settings() {
  const [values, setValues] = useState<SettingsState>({
    position_limit: '',
    max_daily_loss: '',
    simulation_mode: 1,
  });

  useEffect(() => {
    getSettings().then((res) => {
      const map = Object.fromEntries(res.data.map((s) => [s.key, s.value]));
      setValues({
        position_limit: String(map['position_limit'] ?? ''),
        max_daily_loss: String(map['max_daily_loss'] ?? ''),
        simulation_mode: (map['simulation_mode'] as number) ?? 1,
      });
    });
  }, []);

  const save = () =>
    putSettings({
      position_limit: Number(values.position_limit),
      max_daily_loss: Number(values.max_daily_loss),
      simulation_mode: values.simulation_mode,
    });

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
        <FormControlLabel
          label="Simulation Mode (log orders, no real execution)"
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
    </Container>
  );
}
