import { CircularProgress, Box, Button, CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import { AuthProvider, useAuth } from './context/AuthContext';
import { useDashboardSocket } from './hooks/useDashboardSocket';
import Dashboard from './pages/Dashboard';
import Login from './pages/Login';
import Prices from './pages/Prices';
import Settings from './pages/Settings';
import Triangles from './pages/Triangles';

const theme = createTheme({ palette: { mode: 'dark' } });

const PAGES = ['/', '/prices', '/settings', '/triangles'] as const;

function NavBar() {
  const path = window.location.pathname;
  const active = PAGES.includes(path as typeof PAGES[number]) ? path : '/';
  const nav = (href: string, label: string) => (
    <Button
      size="small"
      variant={active === href ? 'contained' : 'text'}
      onClick={() => { window.location.href = href; }}
    >
      {label}
    </Button>
  );
  return (
    <Box sx={{ display: 'flex', gap: 1, px: 2, py: 1, borderBottom: 1, borderColor: 'divider' }}>
      {nav('/', 'Dashboard')}
      {nav('/prices', 'Prices')}
      {nav('/settings', 'Settings')}
      {nav('/triangles', 'Triangles')}
    </Box>
  );
}

function AppRoutes() {
  const { user, isLoading } = useAuth();
  const live = useDashboardSocket();

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
      {path === '/prices'    && <Prices prices={live?.prices ?? []} />}
      {path === '/settings'  && <Settings />}
      {path === '/triangles' && <Triangles />}
      {path !== '/prices' && path !== '/settings' && path !== '/triangles' && <Dashboard />}
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
