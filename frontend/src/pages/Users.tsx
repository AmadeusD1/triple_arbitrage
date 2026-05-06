import { useEffect, useState, type FormEvent } from 'react';
import {
  Alert, Box, Button, FormControl, IconButton, InputLabel, MenuItem,
  Paper, Select, Snackbar, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import { getUsers, createUser, updateUserRole, deleteUser } from '../api/rest';
import type { AppUser } from '../types';
import { useAuth } from '../context/AuthContext';

const ROLES = ['USER', 'QUANT', 'ADMIN'] as const;
type Role = typeof ROLES[number];

const ROLE_DESCRIPTIONS: Record<Role, string> = {
  USER:  'Dashboard, Trades, Feeds',
  QUANT: 'All pages except Users',
  ADMIN: 'Full access',
};

export default function Users() {
  const { user: currentUser } = useAuth();
  const [users, setUsers] = useState<AppUser[]>([]);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [role, setRole] = useState<Role>('USER');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(false);

  const load = () => {
    getUsers().then((r) => setUsers(r.data)).catch(() => {});
  };

  useEffect(load, []);

  const confirmError = confirm && password !== confirm ? 'Passwords do not match.' : '';

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (confirmError) return;
    setError('');
    setLoading(true);
    try {
      await createUser(username, password, role);
      setSuccess(`User "${username}" created successfully.`);
      setUsername('');
      setPassword('');
      setConfirm('');
      setRole('USER');
      load();
    } catch {
      setError('Username already exists or request failed.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ p: 3, maxWidth: 600 }}>
      <Typography variant="h5" gutterBottom>Users</Typography>

      <TableContainer component={Paper} sx={{ mb: 4 }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Username</TableCell>
              <TableCell>Role</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map((u) => (
              <TableRow key={u.id}>
                <TableCell>{u.username}</TableCell>
                <TableCell sx={{ py: 0 }}>
                  <Select
                    size="small"
                    value={u.role}
                    variant="standard"
                    disableUnderline
                    disabled={u.username === currentUser?.username}
                    onChange={async (e) => {
                      await updateUserRole(u.id, e.target.value);
                      load();
                    }}
                    sx={{ fontSize: '0.875rem' }}
                  >
                    {ROLES.map((r) => (
                      <MenuItem key={r} value={r}>{r}</MenuItem>
                    ))}
                  </Select>
                </TableCell>
                <TableCell align="right">
                  <IconButton
                    size="small"
                    color="error"
                    disabled={u.username === currentUser?.username}
                    onClick={async () => {
                      await deleteUser(u.id);
                      setSuccess(`User "${u.username}" deleted.`);
                      load();
                    }}
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <Typography variant="h6" gutterBottom>Create User</Typography>
      <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label="Username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          required
          autoComplete="off"
        />
        <TextField
          label="Password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          autoComplete="new-password"
        />
        <TextField
          label="Confirm Password"
          type="password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          required
          error={!!confirmError}
          helperText={confirmError}
          autoComplete="new-password"
        />
        <FormControl required>
          <InputLabel>Role</InputLabel>
          <Select
            label="Role"
            value={role}
            onChange={(e) => setRole(e.target.value as Role)}
          >
            {ROLES.map((r) => (
              <MenuItem key={r} value={r}>
                {r} — {ROLE_DESCRIPTIONS[r]}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
        {error && <Typography color="error" variant="body2">{error}</Typography>}
        <Button
          type="submit"
          variant="contained"
          disabled={loading || !!confirmError}
          sx={{ alignSelf: 'flex-start' }}
        >
          {loading ? 'Creating…' : 'Create User'}
        </Button>
      </Box>

      <Snackbar
        open={!!success}
        autoHideDuration={4000}
        onClose={() => setSuccess('')}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity="success" onClose={() => setSuccess('')}>{success}</Alert>
      </Snackbar>
    </Box>
  );
}
