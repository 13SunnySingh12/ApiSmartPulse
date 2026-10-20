/**
 * Neon Auth (managed Better Auth) for AUTH_PROVIDER=neon builds. The browser signs in directly with
 * Neon Auth (HttpOnly session cookie on the Neon Auth domain); the API receives the session's
 * short-lived JWT as a bearer token and verifies it against the branch JWKS. The SDK is loaded only
 * in this mode, so local builds never download it.
 */
export const NEON_AUTH = import.meta.env.VITE_AUTH_PROVIDER === 'neon';
const AUTH_URL = import.meta.env.VITE_NEON_AUTH_URL;

let clientPromise = null;
function client() {
  clientPromise ??= import('@neondatabase/neon-js/auth').then(({ createAuthClient }) =>
    createAuthClient(AUTH_URL),
  );
  return clientPromise;
}

/** Same-origin path only: blocks open redirects through ?next= or OAuth callback URLs. */
export function safeNext(value, fallback = '/projects') {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.includes('\\')
    ? value
    : fallback;
}

/** Maps SDK failures to the app's problem shape without echoing provider internals. */
export function neonProblem(error) {
  const status = error?.status ?? 0;
  const code = String(error?.code || '').toUpperCase();
  const base = { status, code: code || 'AUTH_ERROR', errors: [] };
  if (code === 'USER_ALREADY_EXISTS' || code === 'USER_ALREADY_EXISTS_USE_ANOTHER_EMAIL') {
    return { ...base, code: 'EMAIL_TAKEN', detail: 'An account with this email already exists.' };
  }
  if (code === 'INVALID_EMAIL_OR_PASSWORD' || code === 'INVALID_PASSWORD' || code === 'USER_NOT_FOUND') {
    return { ...base, code: 'INVALID_CREDENTIALS', detail: 'Email or password is incorrect.' };
  }
  if (code === 'INVALID_EMAIL') {
    return {
      ...base,
      code: 'VALIDATION_FAILED',
      errors: [{ field: 'email', message: 'Enter a valid email address' }],
    };
  }
  if (code.startsWith('PASSWORD_TOO_')) {
    return {
      ...base,
      code: 'VALIDATION_FAILED',
      errors: [
        { field: 'password', message: 'Use 10–128 characters with at least one letter and one digit' },
      ],
    };
  }
  if (code === 'EMAIL_NOT_VERIFIED') {
    return { ...base, detail: 'Verify your email address first, then log in.' };
  }
  if (status === 429)
    return { ...base, code: 'RATE_LIMITED', detail: 'Too many attempts. Wait a minute and try again.' };
  if (status === 0 || status >= 500) {
    return {
      ...base,
      code: 'AUTH_UNAVAILABLE',
      detail: "Couldn't reach the sign-in service. Try again shortly.",
    };
  }
  return { ...base, detail: 'Sign-in failed. Try again.' };
}

class NeonAuthError extends Error {
  constructor(problem) {
    super(problem.detail || problem.code);
    this.problem = problem;
  }
}

/** Runs an SDK call that may either reject or resolve with { error }. */
async function call(fn) {
  let result;
  try {
    result = await fn(await client());
  } catch (e) {
    throw new NeonAuthError(neonProblem(e?.error ?? e));
  }
  if (result?.error) throw new NeonAuthError(neonProblem(result.error));
  return result?.data;
}

export const neonAuth = {
  signIn: (email, password) => call((c) => c.signIn.email({ email, password })),
  signUp: (name, email, password) => call((c) => c.signUp.email({ name, email, password })),
  /** Leaves the app for Google; Neon Auth redirects back to /auth/callback (or /login on error). */
  signInWithGoogle: (next) => {
    const origin = window.location.origin;
    return call((c) =>
      c.signIn.social({
        provider: 'google',
        callbackURL: `${origin}/auth/callback?next=${encodeURIComponent(safeNext(next))}`,
        errorCallbackURL: `${origin}/login?oauth=failed`,
      }),
    );
  },
  signOut: () => call((c) => c.signOut()),
  /** Current session JWT (the SDK refreshes it when it expires); throws when signed out. */
  token: async () => {
    const data = await call((c) => c.getSession());
    const token = data?.session?.token;
    if (!token) throw new NeonAuthError({ status: 401, code: 'UNAUTHENTICATED', errors: [] });
    return token;
  },
};
