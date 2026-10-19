import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { refreshSession, setAccessToken, setSessionLostHandler } from '../api/client.js';
import { auth } from '../api/endpoints.js';

export const AuthContext = createContext(null);

/** Session state. The access token is in memory only; a silent refresh restores it on reload. */
export function AuthProvider({ children }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState({ status: 'checking', user: null });

  const signedIn = useCallback((session) => {
    setAccessToken(session.accessToken);
    setState({ status: 'signedIn', user: session.user });
  }, []);

  const signedOut = useCallback(() => {
    setAccessToken(null);
    queryClient.clear();
    setState({ status: 'signedOut', user: null });
  }, [queryClient]);

  useEffect(() => {
    setSessionLostHandler(signedOut);
    let cancelled = false;
    refreshSession()
      .then((s) => !cancelled && signedIn(s))
      .catch(() => !cancelled && setState({ status: 'signedOut', user: null }));
    return () => {
      cancelled = true;
    };
  }, [signedIn, signedOut]);

  const value = useMemo(
    () => ({
      ...state,
      login: async (body) => signedIn(await auth.login(body)),
      register: async (body) => signedIn(await auth.register(body)),
      logout: async () => {
        try {
          await auth.logout();
        } finally {
          signedOut();
        }
      },
      setUser: (user) => setState((s) => ({ ...s, user })),
    }),
    [state, signedIn, signedOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  return useContext(AuthContext);
}
