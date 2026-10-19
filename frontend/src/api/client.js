import axios from 'axios';

/**
 * The only HTTP client (TR-008). The access token lives in memory only (SEC-002); the refresh
 * token is an HttpOnly cookie the browser sends to /api/v1/auth/* (withCredentials).
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

let accessToken = null;
let onSessionLost = () => {};
let refreshInFlight = null;

export function setAccessToken(token) {
  accessToken = token;
}

export function setSessionLostHandler(fn) {
  onSessionLost = fn;
}

export const http = axios.create({
  baseURL: `${API_BASE_URL}/api/v1`,
  adapter: 'fetch',
  withCredentials: true,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
});

const isAuthCall = (url = '') => url.startsWith('/auth/');

http.interceptors.request.use((config) => {
  config.headers['X-Request-Id'] = crypto.randomUUID();
  if (accessToken && !isAuthCall(config.url)) config.headers.Authorization = `Bearer ${accessToken}`;
  if (isAuthCall(config.url)) config.headers['X-Requested-With'] = 'XMLHttpRequest';
  return config;
});

/**
 * Rotates the refresh cookie. Single-flight: concurrent callers share one request, because a second
 * use of the same refresh token is treated as theft and revokes the whole session (SEC-003).
 */
export function refreshSession() {
  if (!refreshInFlight) {
    refreshInFlight = http
      .post('/auth/refresh')
      .then((r) => {
        accessToken = r.data.accessToken;
        return r.data;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

http.interceptors.response.use(
  (r) => r,
  async (error) => {
    const original = error.config;
    if (error.response?.status === 401 && original && !original._retried && !isAuthCall(original.url)) {
      original._retried = true;
      try {
        await refreshSession();
        return http(original);
      } catch (refreshError) {
        accessToken = null;
        onSessionLost();
        return Promise.reject(refreshError);
      }
    }
    return Promise.reject(error);
  },
);

/** Normalises any failure into the problem+json shape (§7.3) the UI renders. */
export function problem(error) {
  const data = error?.response?.data;
  if (data && typeof data === 'object' && data.code) {
    return {
      status: error.response.status,
      code: data.code,
      title: data.title,
      detail: data.detail,
      requestId: data.requestId || error.response.headers?.['x-request-id'],
      errors: data.errors || [],
      retryAfter: Number(error.response.headers?.['retry-after']) || null,
      data,
    };
  }
  if (error?.response) {
    return {
      status: error.response.status,
      code: 'HTTP_ERROR',
      detail: 'The server returned an unexpected response.',
      errors: [],
    };
  }
  return {
    status: 0,
    code: 'NETWORK',
    detail: 'Could not reach the server. Check your connection.',
    errors: [],
  };
}
