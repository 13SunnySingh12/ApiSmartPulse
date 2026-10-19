import { describe, expect, it, vi } from 'vitest';
import { http as mswHttp, HttpResponse } from 'msw';
import { http, setAccessToken, setSessionLostHandler } from './client.js';
import { API, server } from '../test/utils.jsx';

describe('axios interceptor (QA-FE-03)', () => {
  it('refreshes once on 401 and retries the original request with the new token', async () => {
    setAccessToken('old-token');
    let refreshes = 0;
    server.use(
      mswHttp.get(`${API}/projects`, ({ request }) =>
        request.headers.get('authorization') === 'Bearer new-token'
          ? HttpResponse.json([{ key: 'PULSE' }])
          : HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
      ),
      mswHttp.post(`${API}/auth/refresh`, ({ request }) => {
        refreshes += 1;
        expect(request.headers.get('x-requested-with')).toBe('XMLHttpRequest');
        return HttpResponse.json({ accessToken: 'new-token', user: { id: 1 } });
      }),
    );
    const [a, b] = await Promise.all([http.get('/projects'), http.get('/projects')]);
    expect(a.data[0].key).toBe('PULSE');
    expect(b.data[0].key).toBe('PULSE');
    expect(refreshes).toBe(1); // single-flight: reuse would revoke the session
  });

  it('calls the session-lost handler when refresh fails', async () => {
    setAccessToken('expired');
    const lost = vi.fn();
    setSessionLostHandler(lost);
    server.use(
      mswHttp.get(`${API}/projects`, () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })),
      mswHttp.post(`${API}/auth/refresh`, () =>
        HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
      ),
    );
    await expect(http.get('/projects')).rejects.toBeTruthy();
    expect(lost).toHaveBeenCalledTimes(1);
  });

  it('sends a request id and never the bearer token to auth endpoints', async () => {
    setAccessToken('secret-token');
    let seen;
    server.use(
      mswHttp.post(`${API}/auth/logout`, ({ request }) => {
        seen = request.headers;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await http.post('/auth/logout');
    expect(seen.get('authorization')).toBeNull();
    expect(seen.get('x-request-id')).toMatch(/^[0-9a-f-]{36}$/);
  });
});
