import { expect, test } from '@playwright/test';
import { ADMIN, API, MANAGER, PASSWORD, api, apiToken, freshProject, login, projectKey, unique } from './helpers.js';

// QA-E2E-01..08 against the local stack with the mock LLM and fake embeddings.

test('QA-E2E-01 register, empty state, promotion, project and member', async ({ page, request }) => {
  const email = `${unique('e2e').toLowerCase()}@demo.local`;
  await page.goto('/register');
  await page.getByLabel('Name').fill('Edge Case');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('E2e-registration-2026');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByText("You're not in any projects yet. Ask a manager to add you.")).toBeVisible();

  const admin = await apiToken(request, ADMIN);
  const users = await api(request, admin, 'GET', '/admin/users');
  const me = users.find((u) => u.email === email);
  await api(request, admin, 'PATCH', `/admin/users/${me.id}`, { role: 'MANAGER' });

  // Role changes revoke sessions (SEC-005): log in again.
  await login(page, email, 'E2e-registration-2026');
  const key = projectKey();
  await page.getByRole('button', { name: 'New project' }).first().click();
  await page.getByLabel('Key').fill(key);
  await page.getByLabel('Name').fill('Registration journey');
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}/board`));

  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByLabel('Add member by email').fill('ravi@demo.local');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('cell', { name: /Ravi Kumar/ })).toBeVisible();
});

test('QA-E2E-02 duplicate warning, create anyway, card with badges', async ({ page, request }) => {
  const { key, token } = await freshProject(request);
  const title = 'Fix the login redirect after the access token refresh on the dashboard';
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title, priority: 'HIGH' });
  await login(page, MANAGER);
  await page.goto(`/projects/${key}/board`);
  // Embeddings sync through the outbox every 10 s; the duplicate check needs the first task indexed.
  await expect(async () => {
    await page.getByRole('button', { name: 'New task' }).first().click();
    await page.getByLabel('Title').fill(title);
    await expect(page.getByText('This looks like existing work')).toBeVisible({ timeout: 3000 });
  }).toPass({ timeout: 60_000, intervals: [5_000] }).catch(async (e) => {
    await page.keyboard.press('Escape');
    throw e;
  });
  await page.getByRole('button', { name: 'Create anyway' }).click();
  await expect(page.getByText(`${key}-2 created`)).toBeVisible();
  const card = page.getByRole('article', { name: title }).nth(1);
  await expect(card.getByText(/High|Urgent/).first()).toBeVisible();
  await expect(card.getByText(/risk|Pending/).first()).toBeVisible();
});

test('QA-E2E-03 subtasks, blocker, blocked move, then unblocked', async ({ page, request }) => {
  const { key, token } = await freshProject(request);
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Blocking task' });
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Blocked task' });
  await login(page, MANAGER);
  await page.goto(`/projects/${key}/tasks/${key}-2`);
  const drawer = page.getByRole('dialog');
  await drawer.getByLabel('New subtask').fill('Write the test');
  await drawer.getByRole('button', { name: 'Add', exact: true }).first().click();
  await expect(drawer.getByText('Write the test')).toBeVisible();
  await drawer.getByLabel('Add blocker').fill(`${key}-1`);
  await drawer.getByRole('button', { name: 'Add', exact: true }).last().click();
  await expect(drawer.getByText('Blocked', { exact: true })).toBeVisible();

  await drawer.getByRole('button', { name: 'More actions' }).click();
  await page.getByRole('menuitem', { name: 'In Progress' }).click();
  await expect(page.getByText(new RegExp(`${key}-2 is blocked by ${key}-1`))).toBeVisible();

  await api(request, token, 'POST', `/tasks/${key}-1/status`, { status: 'DONE', version: 0 });
  await page.reload();
  await page.getByRole('dialog').getByRole('button', { name: 'More actions' }).click();
  await page.getByRole('menuitem', { name: 'In Progress' }).click();
  await expect(page.getByText(`${key}-2 moved to In Progress`)).toBeVisible();
});

test('QA-E2E-04 split a large task, edit suggestions, accept', async ({ page, request }) => {
  const { key, token } = await freshProject(request);
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Build the CSV export', estimateHours: 20 });
  await login(page, MANAGER);
  await page.goto(`/projects/${key}/tasks/${key}-1`);
  await page.getByRole('button', { name: 'Split this task' }).click();
  const dialog = page.getByRole('dialog', { name: 'Split this task' });
  await expect(dialog.getByLabel('Suggestion 1')).toBeVisible();
  await dialog.getByLabel('Suggestion 1').fill('Agree on the CSV columns');
  await dialog.getByRole('button', { name: 'Remove suggestion 4' }).click();
  await dialog.getByRole('button', { name: /Add 3 subtasks/ }).click();
  await expect(page.getByText('3 subtasks added')).toBeVisible();
  await expect(page.getByText('Agree on the CSV columns')).toBeVisible();
});

test('QA-E2E-05 ask with the mock LLM, open a source', async ({ page, request }) => {
  const { key, token } = await freshProject(request);
  await api(request, token, 'POST', `/projects/${key}/tasks`, {
    title: 'Payment webhook retries failing',
    description: 'Retries stopped because the queue was paused during the API review.',
  });
  await login(page, MANAGER);
  await page.goto(`/projects/${key}/ask`);
  await expect(async () => {
    await page.getByLabel('Your question').fill('Why are payment webhook retries failing?');
    await page.getByRole('button', { name: 'Ask' }).click();
    await expect(page.getByRole('link', { name: new RegExp(`${key}-1`) })).toBeVisible({ timeout: 5000 });
  }).toPass({ timeout: 60_000, intervals: [5_000] });
  await expect(page.getByText(/Mock summary/)).toBeVisible();
  await page.getByRole('link', { name: new RegExp(`${key}-1`) }).first().click();
  await expect(page).toHaveURL(new RegExp(`/tasks/${key}-1`));
});

test('QA-E2E-06 workload suggestion, reassign, bars update', async ({ page, request }) => {
  const { key, token } = await freshProject(request, ['asha@demo.local']);
  const members = await api(request, token, 'GET', `/projects/${key}/members`);
  const asha = members.find((m) => m.email === 'asha@demo.local');
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Big low job', priority: 'LOW', estimateHours: 30, assigneeId: asha.userId });
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Big high job', priority: 'HIGH', estimateHours: 30, assigneeId: asha.userId });
  await login(page, MANAGER);
  await page.goto(`/projects/${key}/workload`);
  await expect(page.getByText('150% · Over')).toBeVisible();
  await page.getByRole('button', { name: 'Reassign' }).click();
  await page.getByRole('button', { name: 'Reassign task' }).click();
  await expect(page.getByText(new RegExp(`${key}-1 reassigned`))).toBeVisible();
  await expect(page.getByText('75% · Healthy')).toBeVisible();
});

test('QA-E2E-07 API key: create, call public API, revoke, 401', async ({ page, request }) => {
  await login(page, MANAGER);
  const keysBefore = await api(request, await apiToken(request, MANAGER), 'GET', '/api-keys');
  for (const k of keysBefore.filter((x) => x.status === 'ACTIVE')) {
    await api(request, await apiToken(request, MANAGER), 'DELETE', `/api-keys/${k.id}`);
  }
  await page.goto('/api-keys');
  await page.getByRole('button', { name: 'Create key' }).first().click();
  const name = unique('ci-');
  await page.getByLabel('Name').fill(name);
  await page.getByRole('dialog').getByRole('button', { name: 'Create key' }).click();
  const value = await page.getByLabel(`API key ${name}`).inputValue();
  expect(value).toMatch(/^asp_[0-9a-f]{8}_/);
  await page.getByLabel('I have stored this key safely').check();
  await page.getByRole('button', { name: 'Done' }).click();

  const ok = await request.get(`${API}/api/public/v1/projects`, { headers: { 'X-API-Key': value } });
  expect(ok.status()).toBe(200);
  expect(ok.headers()['x-ratelimit-limit']).toBe('100');

  await page.getByRole('row', { name: new RegExp(name) }).getByRole('button', { name: 'Revoke' }).click();
  await page.getByRole('button', { name: 'Revoke key' }).click();
  await expect(page.getByText(`Key ${name} revoked`)).toBeVisible();
  const denied = await request.get(`${API}/api/public/v1/projects`, { headers: { 'X-API-Key': value } });
  expect(denied.status()).toBe(401);
});

test('QA-E2E-08 mobile: tab bar navigation and "Move to…"', async ({ browser, request }) => {
  const { key, token } = await freshProject(request);
  await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Mobile task' });
  const context = await browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true });
  const page = await context.newPage();
  await login(page, MANAGER);
  const tabbar = page.getByRole('navigation', { name: 'Primary' });
  await expect(tabbar).toBeVisible();
  await tabbar.getByRole('link', { name: 'Keys' }).click();
  await expect(page).toHaveURL(/\/api-keys/);
  await tabbar.getByRole('link', { name: 'Projects' }).click();
  await page.goto(`/projects/${key}/board`);
  await expect(page.getByRole('radiogroup', { name: 'Column' })).toBeVisible();
  await page.getByRole('button', { name: `Move ${key}-1 to another column` }).click();
  await page.getByRole('menuitem', { name: 'In Progress' }).click();
  await expect(page.getByText(`${key}-1 moved to In Progress`)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await context.close();
  expect(PASSWORD).toBeTruthy();
});
