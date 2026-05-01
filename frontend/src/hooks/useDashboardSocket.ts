import { useEffect, useRef, useState } from 'react';
import type { DashboardSnapshot } from '../types';

export function useDashboardSocket(): DashboardSnapshot | null {
  const [data, setData] = useState<DashboardSnapshot | null>(null);
  const ws = useRef<WebSocket | null>(null);

  useEffect(() => {
    let alive = true;
    let retryTimer: ReturnType<typeof setTimeout>;

    function connect() {
      if (!alive) return;
      const url = `ws://${window.location.host}/ws/dashboard`;
      const socket = new WebSocket(url);
      ws.current = socket;
      socket.onmessage = (e: MessageEvent<string>) => {
        if (alive) setData(JSON.parse(e.data) as DashboardSnapshot);
      };
      socket.onclose = () => {
        if (alive) retryTimer = setTimeout(connect, 2000);
      };
    }

    // Defer to next tick so StrictMode's cleanup can cancel before the
    // socket is created, avoiding "closed before established" errors.
    retryTimer = setTimeout(connect, 0);

    return () => {
      alive = false;
      clearTimeout(retryTimer);
      if (ws.current) {
        ws.current.onclose = null;
        ws.current.close();
      }
    };
  }, []);

  return data;
}
