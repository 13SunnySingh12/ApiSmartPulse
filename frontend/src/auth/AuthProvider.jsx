import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  problem,
  refreshSession,
  setAccessToken,
  setSessionLostHandler,
  setSessionRefresher,
} from '../api/client.js';
import { auth, me } from '../api/endpoints.js';
import { NEON_AUTH, neonAuth } from './neonAuth.js';

export const AuthContext = createContext(null);

if (NEON_AUTH) setSessionRefresher(async () => ({ accessToken: await neonAuth.token() }));

/**
 * Neon Auth mode: the Neon session gives a JWT; the API turns it into an app user (created on first
 * use). If the API refuses the identity (deactivated, email conflict) the Neon session is ended too,
 * so the browser isn't left signed in to the provider but locked out of the app.
 */
async function neonSession() {
  const { accessToken } = await refreshSession();
  try {
    return { accessToken, user: await me.get() };
  } catch (e) {
    const p = problem(e);
    if (p.status === 403 || p.status === 409) await neonAuth.signOut().catch(() => {});
    throw e;
  }
}

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
    (NEON_AUTH ? neonSession() : refreshSession())
      .then((s) => !cancelled && signedIn(s))
      .catch(() => !cancelled && setState({ status: 'signedOut', user: null }));
    return () => {
      cancelled = true;
    };
  }, [signedIn, signedOut]);

  const value = useMemo(
    () => ({
      ...state,
      provider: NEON_AUTH ? 'neon' : 'local',
      login: async ({ email, password }) => {
        if (!NEON_AUTH) return signedIn(await auth.login({ email, password }));
        await neonAuth.signIn(email, password);
        signedIn(await neonSession());
      },
      register: async ({ name, email, password }) => {
        if (!NEON_AUTH) return signedIn(await auth.register({ name, email, password }));
        await neonAuth.signUp(name, email, password);
        signedIn(await neonSession());
      },
      loginWithGoogle: (next) => neonAuth.signInWithGoogle(next),
      logout: async () => {
        try {
          await (NEON_AUTH ? neonAuth.signOut() : auth.logout());
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
