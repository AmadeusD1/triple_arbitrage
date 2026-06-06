import { useCallback, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';

/**
 * Drop-in replacement for useState that persists to sessionStorage.
 * State survives in-session page navigations (full reloads) but resets when
 * the browser tab is closed, matching the expected "session memory" behaviour.
 *
 * @param key      Unique storage key — use the pattern "page:field" to avoid collisions.
 * @param initial  Initial value when nothing is stored yet.
 */
export function useSessionState<T>(key: string, initial: T): [T, Dispatch<SetStateAction<T>>] {
  const [state, setState] = useState<T>(() => {
    try {
      const stored = sessionStorage.getItem(key);
      return stored !== null ? (JSON.parse(stored) as T) : initial;
    } catch {
      return initial;
    }
  });

  const set: Dispatch<SetStateAction<T>> = useCallback(
    (action) => {
      setState((prev) => {
        const next = typeof action === 'function'
          ? (action as (p: T) => T)(prev)
          : action;
        try { sessionStorage.setItem(key, JSON.stringify(next)); } catch { /* storage quota */ }
        return next;
      });
    },
    [key],
  );

  return [state, set];
}
