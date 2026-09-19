import { Component, useEffect, useRef, useState } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import {
  CircularProgress, Box, Button, CssBaseline, ThemeProvider, createTheme,
  Drawer, IconButton, List, ListItemButton, ListItemText, Divider,
  useTheme, useMediaQuery,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import { AuthProvider, useAuth } from './context/AuthContext';
import { useDashboardSocket } from './hooks/useDashboardSocket';
import Dashboard from './pages/Dashboard';
import Login from './pages/Login';
import Analytics from './pages/Analytics';
import OpenOrders from './pages/OpenOrders';
import Positions from './pages/Positions';
import CcyRates from './pages/CcyRates';
import CurrencyRates from './pages/CurrencyRates';
import ManualTrade from './pages/ManualTrade';
import MissedOpportunities from './pages/MissedOpportunities';
import Prices from './pages/Prices';
import Settings from './pages/Settings';
import Trades from './pages/Trades';
import Triangles from './pages/Triangles';
import Users from './pages/Users';

const theme = createTheme({ palette: { mode: 'dark' } });

class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { error: null };
  }
  static getDerivedStateFromError(error: Error) { return { error }; }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack);
  }
  render() {
    if (this.state.error) {
      return (
        <Box sx={{ p: 4, color: 'error.main' }}>
          <strong>Something went wrong.</strong>
          <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', marginTop: 8 }}>
            {this.state.error.message}
          </pre>
          <Button variant="outlined" size="small" onClick={() => this.setState({ error: null })}>
            Retry
          </Button>
        </Box>
      );
    }
    return this.props.children;
  }
}

const PAGES = ['/', '/trades', '/missed-opportunities', '/positions', '/open-orders', '/analytics', '/prices', '/ccy-rates', '/currency-rates', '/manual-trade', '/settings', '/triangles', '/users'] as const;

// USER  → dashboard, trades, feeds only
// QUANT → everything except /users
// ADMIN → everything
function canAccess(role: string, path: string): boolean {
  if (role === 'ADMIN') return true;
  if (role === 'QUANT') return path !== '/users';
  return ['/', '/trades', '/prices', '/ccy-rates', '/currency-rates'].includes(path);
}

function NavBar({ flashTrigger }: { flashTrigger: number }) {
  const path = window.location.pathname;
  const active = PAGES.includes(path as typeof PAGES[number]) ? path : '/';
  const { logout, user } = useAuth();
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Flashes the nav strip twice within 1.5s whenever a real (non-simulation) trade fires -
  // see AppRoutes, which increments flashTrigger on tradeInProgress's false->true edge.
  const [flashOn, setFlashOn] = useState(false);
  useEffect(() => {
    if (flashTrigger === 0) return;
    const timers = [0, 350, 700, 1050].map((delay, i) =>
      setTimeout(() => setFlashOn(i % 2 === 0), delay));
    return () => timers.forEach(clearTimeout);
  }, [flashTrigger]);

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

  const navItems: { href: string; label: string }[] = [
    { href: '/',                      label: 'Dashboard' },
    { href: '/trades',                label: 'Trades' },
    { href: '/missed-opportunities',  label: 'Missed' },
    ...(canAccess(role, '/positions')    ? [{ href: '/positions',    label: 'Positions' }]        : []),
    ...(canAccess(role, '/open-orders')  ? [{ href: '/open-orders',  label: 'Open Orders' }]      : []),
    ...(canAccess(role, '/analytics')   ? [{ href: '/analytics',    label: 'Analytics' }]          : []),
    { href: '/prices',                label: 'Tickers' },
    { href: '/ccy-rates',             label: 'CCY Rates' },
    { href: '/currency-rates',        label: 'Fiat Rates' },
    ...(canAccess(role, '/manual-trade') ? [{ href: '/manual-trade', label: 'Manual Trade' }]     : []),
    ...(canAccess(role, '/triangles')   ? [{ href: '/triangles',    label: 'Exchange Settings' }] : []),
    ...(canAccess(role, '/settings')    ? [{ href: '/settings',     label: 'Settings' }]          : []),
    ...(canAccess(role, '/users')       ? [{ href: '/users',        label: 'Users' }]             : []),
  ];

  return (
    <Box
      component="nav"
      sx={{
        position: 'sticky',
        top: 0,
        zIndex: (t) => t.zIndex.appBar,
        bgcolor: flashOn ? '#ffc107' : 'background.paper',
        transition: 'background-color 0.15s ease',
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', px: 2, py: 1 }}>
        {isMobile ? (
          <>
            <IconButton size="small" edge="start" color="inherit" aria-label="open navigation"
              onClick={() => setDrawerOpen(true)} sx={{ mr: 1 }}>
              <MenuIcon />
            </IconButton>
            <Box sx={{ flex: 1 }} />
            <Box sx={{ color: 'text.secondary', fontSize: '0.8rem', mr: 1 }}>{user?.username}</Box>
            <Button size="small" color="inherit" onClick={() => void logout()}>Logout</Button>
          </>
        ) : (
          <>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              {nav('/', 'Dashboard')}
              {nav('/trades', 'Trades')}
              {nav('/missed-opportunities', 'Missed')}
              {canAccess(role, '/positions')   && nav('/positions',   'Positions')}
              {canAccess(role, '/open-orders') && nav('/open-orders', 'Open Orders')}
              {canAccess(role, '/analytics')  && nav('/analytics',   'Analytics')}
            </Box>
            <Box sx={{ flex: 1 }} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              {nav('/prices', 'Tickers')}
              {nav('/ccy-rates', 'CCY Rates')}
              {nav('/currency-rates', 'Fiat Rates')}
              {canAccess(role, '/manual-trade') && nav('/manual-trade', 'Manual Trade')}
              {canAccess(role, '/triangles') && nav('/triangles', 'Exchange Settings')}
              {canAccess(role, '/settings')  && nav('/settings',  'Settings')}
              {canAccess(role, '/users')     && nav('/users',     'Users')}
              <Box sx={{ color: 'text.secondary', fontSize: '0.8rem', mr: 1, ml: 1 }}>{user?.username}</Box>
              <Button size="small" color="inherit" onClick={() => void logout()}>Logout</Button>
            </Box>
          </>
        )}
      </Box>

      <Drawer anchor="left" open={drawerOpen} onClose={() => setDrawerOpen(false)}
        slotProps={{ paper: { sx: { width: 240 } } }}>
        <Box sx={{ py: 1, px: 2 }}>
          <Box sx={{ color: 'text.secondary', fontSize: '0.85rem' }}>{user?.username}</Box>
        </Box>
        <Divider />
        <List dense disablePadding>
          {navItems.map(({ href, label }) => (
            <ListItemButton key={href} selected={active === href}
              onClick={() => { setDrawerOpen(false); window.location.href = href; }}>
              <ListItemText primary={label} />
            </ListItemButton>
          ))}
        </List>
        <Divider />
        <Box sx={{ p: 1 }}>
          <Button fullWidth size="small" color="inherit" onClick={() => void logout()}>Logout</Button>
        </Box>
      </Drawer>
    </Box>
  );
}

function AppRoutes() {
  const { user, isLoading } = useAuth();
  const live = useDashboardSocket();

  // A real trade just started (tradeInProgress is only ever true for real trades, never
  // simulations - see AutoTrader's settlement gate). Fires the nav-strip flash regardless of
  // which tab is currently open, since NavBar is always mounted here above the active page.
  const tradeInProgress = live?.tradeInProgress;
  const [flashTrigger, setFlashTrigger] = useState(0);
  const prevTradeInProgress = useRef<boolean | null>(null);
  useEffect(() => {
    if (tradeInProgress == null) return;
    if (prevTradeInProgress.current === false && tradeInProgress === true) {
      setFlashTrigger((f) => f + 1);
    }
    prevTradeInProgress.current = tradeInProgress;
  }, [tradeInProgress]);

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
      <NavBar flashTrigger={flashTrigger} />
      <Box component="main" sx={{ flex: 1 }}>
        {path === '/trades'             && <Trades liveTrades={live?.recentTrades} />}
        {path === '/missed-opportunities' && <MissedOpportunities rows={live?.recentMissedOpportunities ?? []} />}
        {path === '/positions'    && canAccess(role, path) && <Positions />}
        {path === '/open-orders'  && canAccess(role, path) && <OpenOrders />}
        {path === '/analytics'   && canAccess(role, path) && <Analytics />}
        {path === '/prices'       && <Prices      prices={live?.prices ?? []} />}
        {path === '/ccy-rates'    && <CcyRates />}
        {path === '/currency-rates'     && <CurrencyRates     rates={live?.fxRates ?? {}} />}
        {path === '/manual-trade' && canAccess(role, path) && <ManualTrade />}
        {path === '/settings'     && canAccess(role, path) && <Settings />}
        {path === '/triangles'    && canAccess(role, path) && <Triangles prices={live?.prices ?? []} exchangeRunning={live?.exchangeRunning ?? {}} />}
        {path === '/users'        && canAccess(role, path) && <Users />}
        {path !== '/trades' && path !== '/missed-opportunities' && path !== '/positions' &&
         path !== '/open-orders' && path !== '/analytics' && path !== '/prices' && path !== '/ccy-rates' &&
         path !== '/currency-rates' && path !== '/manual-trade' &&
         path !== '/settings' && path !== '/triangles' && path !== '/users' && <Dashboard />}
      </Box>
    </Box>
  );
}

export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <ErrorBoundary>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </ErrorBoundary>
    </ThemeProvider>
  );
}
