import { createContext, useContext, useEffect, useState } from 'react';

const KEY = 'asp-theme';
const ThemeContext = createContext({ theme: 'system', setTheme: () => {} });

/** Storage can throw (private mode, blocked site data): the theme then simply isn't remembered. */
function read() {
  try {
    const v = window.localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

function write(v) {
  try {
    if (v === 'system') window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, v);
  } catch {
    /* not persisted — still applied for this session */
  }
}

/** System / Light / Dark (DS-040, FR-081). System follows prefers-color-scheme via CSS. */
export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(read);
  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', theme);
  }, [theme]);
  const setTheme = (v) => {
    write(v);
    setThemeState(v);
  };
  return <ThemeContext.Provider value={{ theme, setTheme }}>{children}</ThemeContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export const useTheme = () => useContext(ThemeContext);
