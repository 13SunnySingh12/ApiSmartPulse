import { expect } from '@playwright/test';

export const API = process.env.E2E_API_URL || 'http://localhost:8080';
export const PASSWORD = process.env.E2E_PASSWORD; // SEED_DEMO_PASSWORD of the local/CI stack
export const ADMIN = process.env.E2E_ADMIN_EMAIL || 'admin@demo.local';
export const MANAGER = 'priya@demo.local';

if (!PASSWORD) throw new Error("Set E2E_PASSWORD to the stack's SEED_DEMO_PASSWORD");

export const unique = (prefix) => `${prefix}${Date.now().toString(36).slice(-5).toUpperCase()}`;

/** Projects keys are 2–6 letters: derive one from the clock. */
export function projectKey() {
  const n = Date.now() % 26 ** 5;
  let s = 'E';
  for (let i = 0, v = n; i < 5; i++, v = Math.floor(v / 26)) s += String.fromCharCode(65 + (v % 26));
  return s;
}

export async function login(page, email, password = PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(/\/projects/);
}

/** Direct API session for setup steps that aren't the subject of a test. */
export async function apiToken(request, email, password = PASSWORD) {
  const r = await request.post(`${API}/api/v1/auth/login`, { data: { email, password } });
  expect(r.ok(), await r.text()).toBeTruthy();
  return (await r.json()).accessToken;
}

export async function api(request, token, method, path, data) {
  const r = await request.fetch(`${API}/api/v1${path}`, {
    method,
    data,
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(r.status(), `${method} ${path}: ${await r.text()}`).toBeLessThan(400);
  return r.status() === 204 ? null : r.json();
}

/** A fresh project owned by the manager, so each journey is independent of seed details. */
export async function freshProject(request, members = []) {
  const token = await apiToken(request, MANAGER);
  const key = projectKey();
  await api(request, token, 'POST', '/projects', { key, name: `E2E ${key}` });
  for (const m of members)
    await api(request, token, 'POST', `/projects/${key}/members`, { userEmail: m, capacityHours: 40 });
  return { key, token };
}

/**
 * The visible toast list (its text is also mirrored in a hidden live region). Located by class:
 * while a modal is open, Radix marks everything outside it aria-hidden.
 */
export const toasts = (page) => page.locator('.toasts');
