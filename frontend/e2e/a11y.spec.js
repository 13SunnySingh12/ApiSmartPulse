import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { ADMIN, MANAGER, apiToken, api, freshProject, login } from './helpers.js';

// QA-A11Y-01: zero serious/critical axe violations on the main pages, in light and dark themes.
const pages = (key) => [
  ['projects', '/projects'],
  ['board', `/projects/${key}/board`],
  ['task detail', `/projects/${key}/tasks/${key}-1`],
  ['ask', `/projects/${key}/ask`],
  ['workload', `/projects/${key}/workload`],
  ['analytics', `/projects/${key}/analytics`],
  ['settings', `/projects/${key}/settings`],
  ['api keys', '/api-keys'],
  ['profile', '/profile'],
];

async function scan(page, label) {
  await page.waitForLoadState('networkidle');
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const serious = result.violations.filter((v) => ['serious', 'critical'].includes(v.impact));
  expect(serious.map((v) => `${label}: ${v.id} (${v.nodes.length}) ${v.help}`)).toEqual([]);
}

for (const scheme of ['light', 'dark']) {
  test(`axe: main pages in ${scheme} theme`, async ({ browser, request }) => {
    const { key, token } = await freshProject(request, ['ravi@demo.local']);
    await api(request, token, 'POST', `/projects/${key}/tasks`, { title: 'Accessibility check task', estimateHours: 4 });
    const context = await browser.newContext({ colorScheme: scheme });
    const page = await context.newPage();

    await page.goto('/login');
    await scan(page, `login (${scheme})`);
    await page.goto('/register');
    await scan(page, `register (${scheme})`);

    await login(page, MANAGER);
    for (const [label, path] of pages(key)) {
      await page.goto(path);
      await scan(page, `${label} (${scheme})`);
    }
    await context.close();

    const admin = await browser.newContext({ colorScheme: scheme });
    const adminPage = await admin.newPage();
    await login(adminPage, ADMIN);
    for (const path of ['/admin/users', '/admin/ml']) {
      await adminPage.goto(path);
      await scan(adminPage, `${path} (${scheme})`);
    }
    await admin.close();
    expect(await apiToken(request, MANAGER)).toBeTruthy();
  });
}
