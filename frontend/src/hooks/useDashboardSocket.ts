import { useEffect, useRef, useState } from 'react';
import type { DashboardSnapshot } from '../types';

export function useDashboardSocket(): DashboardSnapshot | null {
  const [data, setData] = useState<DashboardSnapshot | null>(null);
  const ws = useRef<WebSocket | null>(null);

  useEffect(() => {
    function connect() {
      const url = `ws://${window.location.host}/ws/dashboard`;
      ws.current = new WebSocket(url);
      ws.current.onmessage = (e: MessageEvent<string>) =>
        setData(JSON.parse(e.data) as DashboardSnapshot);
      ws.current.onclose = () => setTimeout(connect, 2000);
    }
    connect();
    return () => ws.current?.close();
  }, []);

  return data;
}
