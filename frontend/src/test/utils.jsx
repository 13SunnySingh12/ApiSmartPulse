import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import axe from 'axe-core';
import { ToastProvider } from '../components/ui/Toast.jsx';
import { TooltipProvider } from '../components/ui/Menu.jsx';
import { ThemeProvider } from '../hooks/useTheme.jsx';

export const API = 'http://api.test/api/v1';
export const server = setupServer();

export function renderWithProviders(ui, { route = '/', path = '*' } = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <ToastProvider>
          <TooltipProvider>
            <MemoryRouter initialEntries={[route]}>
              <Routes>
                <Route path={path} element={ui} />
              </Routes>
            </MemoryRouter>
          </TooltipProvider>
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

/** QA-A11Y-01 (component level): no serious or critical axe violations. */
export async function expectNoSeriousA11yViolations(container) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } }); // jsdom has no layout
  const serious = result.violations.filter((v) => ['serious', 'critical'].includes(v.impact));
  if (serious.length) throw new Error(serious.map((v) => `${v.id}: ${v.help}`).join('\n'));
}
