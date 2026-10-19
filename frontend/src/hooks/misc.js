import { useEffect, useState, useSyncExternalStore } from 'react';

/** Page titles per route (DS-039), e.g. "Board · PULSE · ApiSmartPulse". */
export function useDocumentTitle(...parts) {
  const title = [...parts.filter(Boolean), 'ApiSmartPulse'].join(' · ');
  useEffect(() => {
    document.title = title;
  }, [title]);
}

export function useMediaQuery(query) {
  return useSyncExternalStore(
    (cb) => {
      const m = window.matchMedia(query);
      m.addEventListener('change', cb);
      return () => m.removeEventListener('change', cb);
    },
    () => window.matchMedia(query).matches,
    () => false,
  );
}

export function useDebounced(value, ms) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}
