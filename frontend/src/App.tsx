import { CircularProgress, Box, Button, CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import { AuthProvider, useAuth } from './context/AuthContext';
import { useDashboardSocket } from './hooks/useDashboardSocket';
import Dashboard from './pages/Dashboard';
import Login from './pages/Login';
import OpenOrders from './pages/OpenOrders';
import Positions from './pages/Positions';
import CurrencyRates from './pages/CurrencyRates';
import Prices from './pages/Prices';
import Settings from './pages/Settings';
import Trades from './pages/Trades';
import Triangles from './pages/Triangles';
import Users from './pages/Users';

const theme = createTheme({ palette: { mode: 'dark' } });

const PAGES = ['/', '/trades', '/positions', '/open-orders', '/prices', '/currency-rates', '/settings', '/triangles', '/users'] as const;

// USER  → dashboard, trades, feeds only
// QUANT → everything except /users
// ADMIN → everything
function canAccess(role: string, path: string): boolean {
  if (role === 'ADMIN') return true;
  if (role === 'QUANT') return path !== '/users';
  return ['/', '/trades', '/prices', '/currency-rates'].includes(path);
}

function NavBar() {
  const path = window.location.pathname;
  const active = PAGES.includes(path as typeof PAGES[number]) ? path : '/';
  const { logout, user } = useAuth();

  const nav = (href: string, label: string) => (
    <Button
      size="small"
      variant={active === href ? 'contained' : 'text'}
      onClick={() => { window.location.href = href; }}
    >
      {label}
    </Button>
  );

  const role = user?.role ?? '';

  return (
    <Box
      component="nav"
      sx={{
        position: 'sticky',
        top: 0,
        zIndex: (t) => t.zIndex.appBar,
        bgcolor: 'background.paper',
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1 }}>
        {/* Left: primary navigation */}
        {nav('/', 'Dashboard')}
        {nav('/trades', 'Trades')}
        {canAccess(role, '/positions')   && nav('/positions',   'Positions')}
        {canAccess(role, '/open-orders') && nav('/open-orders', 'Open Orders')}

        <Box sx={{ flex: 1 }} />

        {/* Right: config / data views */}
        {nav('/prices', 'Feeds')}
        {nav('/currency-rates', 'Currency Rates')}
        {canAccess(role, '/triangles') && nav('/triangles', 'Exchange Settings')}
        {canAccess(role, '/settings')  && nav('/settings',  'Settings')}
        {canAccess(role, '/users')     && nav('/users',     'Users')}

        <Box sx={{ color: 'text.secondary', fontSize: '0.8rem', mr: 1, ml: 1 }}>{user?.username}</Box>
        <Button size="small" color="inherit" onClick={() => void logout()}>Logout</Button>
      </Box>
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
  const role = user.role;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <NavBar />
      <Box component="main" sx={{ flex: 1 }}>
        {path === '/trades'       && <Trades      trades={live?.recentTrades ?? []} />}
        {path === '/positions'    && canAccess(role, path) && <Positions />}
        {path === '/open-orders'  && canAccess(role, path) && <OpenOrders />}
        {path === '/prices'       && <Prices      prices={live?.prices ?? []} />}
        {path === '/currency-rates'     && <CurrencyRates     rates={live?.fxRates ?? {}} />}
        {path === '/settings'     && canAccess(role, path) && <Settings />}
        {path === '/triangles'    && canAccess(role, path) && <Triangles prices={live?.prices ?? []} />}
        {path === '/users'        && canAccess(role, path) && <Users />}
        {path !== '/trades' && path !== '/positions' && path !== '/open-orders' &&
         path !== '/prices' && path !== '/currency-rates'  && path !== '/settings'  &&
         path !== '/triangles' && path !== '/users'  && <Dashboard />}
      </Box>
    </Box>
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
