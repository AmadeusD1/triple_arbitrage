import { useState } from 'react';
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
import OpenOrders from './pages/OpenOrders';
import Positions from './pages/Positions';
import CurrencyRates from './pages/CurrencyRates';
import MissedOpportunities from './pages/MissedOpportunities';
import Prices from './pages/Prices';
import Settings from './pages/Settings';
import Trades from './pages/Trades';
import Triangles from './pages/Triangles';
import Users from './pages/Users';

const theme = createTheme({ palette: { mode: 'dark' } });

const PAGES = ['/', '/trades', '/missed-opportunities', '/positions', '/open-orders', '/prices', '/currency-rates', '/settings', '/triangles', '/users'] as const;

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
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  const [drawerOpen, setDrawerOpen] = useState(false);

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
    { href: '/prices',                label: 'Feeds' },
    { href: '/currency-rates',        label: 'Currency Rates' },
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
        bgcolor: 'background.paper',
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
            </Box>
            <Box sx={{ flex: 1 }} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              {nav('/prices', 'Feeds')}
              {nav('/currency-rates', 'Currency Rates')}
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
        {path === '/trades'             && <Trades />}
        {path === '/missed-opportunities' && <MissedOpportunities rows={live?.recentMissedOpportunities ?? []} />}
        {path === '/positions'    && canAccess(role, path) && <Positions />}
        {path === '/open-orders'  && canAccess(role, path) && <OpenOrders />}
        {path === '/prices'       && <Prices      prices={live?.prices ?? []} />}
        {path === '/currency-rates'     && <CurrencyRates     rates={live?.fxRates ?? {}} />}
        {path === '/settings'     && canAccess(role, path) && <Settings />}
        {path === '/triangles'    && canAccess(role, path) && <Triangles prices={live?.prices ?? []} />}
        {path === '/users'        && canAccess(role, path) && <Users />}
        {path !== '/trades' && path !== '/missed-opportunities' && path !== '/positions' &&
         path !== '/open-orders' && path !== '/prices' && path !== '/currency-rates' &&
         path !== '/settings' && path !== '/triangles' && path !== '/users' && <Dashboard />}
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
