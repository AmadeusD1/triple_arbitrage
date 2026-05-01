import { CircularProgress, Box, Button, CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import { AuthProvider, useAuth } from './context/AuthContext';
import Dashboard from './pages/Dashboard';
import Login from './pages/Login';
import Settings from './pages/Settings';
import Triangles from './pages/Triangles';

const theme = createTheme({ palette: { mode: 'dark' } });

function NavBar() {
  const path = window.location.pathname;
  const nav = (href: string, label: string) => (
    <Button
      size="small"
      variant={path === href || (href === '/' && path !== '/settings' && path !== '/triangles') ? 'contained' : 'text'}
      onClick={() => { window.location.href = href; }}
    >
      {label}
    </Button>
  );
  return (
    <Box sx={{ display: 'flex', gap: 1, px: 2, py: 1, borderBottom: 1, borderColor: 'divider' }}>
      {nav('/', 'Dashboard')}
      {nav('/settings', 'Settings')}
      {nav('/triangles', 'Triangles')}
    </Box>
  );
}

function AppRoutes() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return (
      <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!user) return <Login />;

  const path = window.location.pathname;
  return (
    <>
      <NavBar />
      {path === '/settings' && <Settings />}
      {path === '/triangles' && <Triangles />}
      {path !== '/settings' && path !== '/triangles' && <Dashboard />}
    </>
  );
}

export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </ThemeProvider>
  );
}
