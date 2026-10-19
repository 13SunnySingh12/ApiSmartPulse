import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { DndContext } from '@dnd-kit/core';
import { API, renderWithProviders, server, expectNoSeriousA11yViolations } from '../test/utils.jsx';
import { AuthContext, AuthProvider } from '../auth/AuthProvider.jsx';
import { LoginPage } from './auth/AuthPages.jsx';
import { ProjectContext } from './projects/ProjectLayout.jsx';
import { TaskDetail } from './task/TaskDetail.jsx';
import { TaskCard } from './board/TaskCard.jsx';
import AskPage from './ask/AskPage.jsx';
import ApiKeysPage from './apikeys/ApiKeysPage.jsx';

const PROJECT = { id: 1, key: 'PULSE', name: 'Pulse', status: 'ACTIVE', canManage: true, version: 0 };
const withProject = (ui) => <ProjectContext.Provider value={PROJECT}>{ui}</ProjectContext.Provider>;
const noSession = () =>
  server.use(
    http.post(`${API}/auth/refresh`, () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })),
  );

const TASK = {
  id: 1,
  key: 'PULSE-1',
  projectKey: 'PULSE',
  projectArchived: false,
  title: 'Fix login redirect',
  description: 'Steps',
  status: 'TODO',
  priority: 'HIGH',
  dueDate: null,
  assignee: null,
  creator: { id: 1, name: 'Priya' },
  estimateHours: 6,
  actualHours: null,
  labels: [],
  subtaskSummary: { done: 0, total: 0 },
  subtasks: [],
  blockedBy: [],
  blocks: [],
  commentCount: 0,
  prediction: {
    status: 'OK',
    predictedHours: 6,
    riskLevel: 'LOW',
    suggestedPriority: 'HIGH',
    priorityConfidence: 0.6,
    source: 'HEURISTIC',
  },
  activity: [],
  canDelete: true,
  version: 3,
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
};

describe('Login (QA-FE-02)', () => {
  const renderLogin = () =>
    renderWithProviders(
      <AuthProvider>
        <LoginPage />
      </AuthProvider>,
    );

  it('shows field errors and focuses the error summary', async () => {
    noSession();
    renderLogin();
    await userEvent.click(await screen.findByRole('button', { name: 'Log in' }));
    const summary = await screen.findByRole('alert');
    expect(summary).toHaveFocus();
    expect(within(summary).getByRole('link', { name: 'Enter a valid email address' })).toHaveAttribute(
      'href',
      '#login-email',
    );
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows the generic server message on 401', async () => {
    noSession();
    server.use(
      http.post(`${API}/auth/login`, () =>
        HttpResponse.json(
          { code: 'INVALID_CREDENTIALS', detail: 'Email or password is incorrect.' },
          { status: 401 },
        ),
      ),
    );
    const { container } = renderLogin();
    await userEvent.type(await screen.findByLabelText('Email'), 'a@b.co');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-pass-1');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByText('Email or password is incorrect.')).toBeInTheDocument();
    await expectNoSeriousA11yViolations(container);
  });
});

describe('Task detail conflict (QA-FE-05)', () => {
  it('keeps the edited text and offers Reload on 409 CONFLICT_STALE', async () => {
    server.use(
      http.get(`${API}/tasks/PULSE-1`, () => HttpResponse.json(TASK)),
      http.get(`${API}/projects/PULSE/members`, () => HttpResponse.json([])),
      http.get(`${API}/projects/PULSE/labels`, () => HttpResponse.json([])),
      http.get(`${API}/tasks/PULSE-1/comments`, () => HttpResponse.json({ items: [], totalItems: 0 })),
      http.patch(`${API}/tasks/PULSE-1`, () =>
        HttpResponse.json({ code: 'CONFLICT_STALE', detail: 'changed', currentVersion: 4 }, { status: 409 }),
      ),
    );
    renderWithProviders(
      <AuthContext.Provider value={{ user: { id: 1, role: 'MANAGER' } }}>
        {withProject(<TaskDetail taskKey="PULSE-1" onClose={() => {}} />)}
      </AuthContext.Provider>,
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    const title = screen.getByLabelText('Title');
    await userEvent.clear(title);
    await userEvent.type(title, 'My unsaved title');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('This task changed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Reload latest/ })).toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toHaveValue('My unsaved title');
  });
});

describe('Ask degraded mode (QA-FE-06)', () => {
  it('shows a neutral notice and still lists sources', async () => {
    server.use(
      http.post(`${API}/projects/PULSE/ask`, () =>
        HttpResponse.json({
          answer: null,
          grounded: false,
          degraded: true,
          reason: 'LLM_QUOTA_EXHAUSTED',
          sources: [
            {
              taskKey: 'PULSE-31',
              title: 'Export CSV',
              status: 'DONE',
              sourceType: 'TASK',
              snippet: 'Blocked until review',
              similarity: 0.82,
            },
          ],
        }),
      ),
    );
    renderWithProviders(withProject(<AskPage />));
    await userEvent.type(screen.getByLabelText('Your question'), 'What slipped last month?');
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }));
    const notice = await screen.findByText(/usage limit reached/);
    expect(notice.closest('.notice')).toHaveClass('notice--info'); // never styled as a red error
    expect(screen.getByRole('link', { name: /PULSE-31/ })).toHaveAttribute(
      'href',
      '/projects/PULSE/tasks/PULSE-31',
    );
    expect(screen.getByText(/82% match/)).toBeInTheDocument();
  });
});

describe('API key created dialog (QA-FE-07)', () => {
  it('cannot be closed until the user confirms storing the key; Copy shows success', async () => {
    const writeText = vi.fn().mockResolvedValue();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    server.use(
      http.get(`${API}/api-keys`, () => HttpResponse.json([])),
      http.post(`${API}/api-keys`, () =>
        HttpResponse.json(
          {
            id: 1,
            name: 'ci-script',
            key: 'asp_0123abcd_' + 'x'.repeat(43),
            prefix: '0123abcd',
            notice: 'Copy this key now. It will not be shown again.',
          },
          { status: 201 },
        ),
      ),
    );
    renderWithProviders(
      <AuthContext.Provider value={{ user: { id: 1, role: 'MEMBER' } }}>
        <ApiKeysPage />
      </AuthContext.Provider>,
    );
    await userEvent.click((await screen.findAllByRole('button', { name: 'Create key' }))[0]);
    await userEvent.type(screen.getByLabelText('Name'), 'ci-script');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Create key' }));
    const done = await screen.findByRole('button', { name: 'Done' });
    expect(done).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Copy key' }));
    expect(writeText).toHaveBeenCalledWith(expect.stringMatching(/^asp_0123abcd_/));
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('I have stored this key safely'));
    expect(done).not.toHaveAttribute('aria-disabled');
  });
});

describe('Board "Move to…" (QA-FE-09)', () => {
  it('changes status from the keyboard without drag-and-drop', async () => {
    const onMove = vi.fn();
    const summary = { ...TASK, blocked: false };
    renderWithProviders(
      <DndContext>
        <TaskCard task={summary} projectKey="PULSE" onMove={onMove} column="TODO" index={0} />
      </DndContext>,
    );
    const trigger = screen.getByRole('button', { name: 'Move PULSE-1 to another column' });
    trigger.focus();
    await userEvent.keyboard('{Enter}');
    const item = await screen.findByRole('menuitem', { name: 'In Progress' });
    item.focus();
    await userEvent.keyboard('{Enter}');
    expect(onMove).toHaveBeenCalledWith(summary, 'IN_PROGRESS');
  });
});
