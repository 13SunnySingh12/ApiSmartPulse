import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate, Outlet, RouterProvider, useLocation } from 'react-router';
import { useAuth } from './auth/AuthProvider.jsx';
import { AppShell } from './components/layout/AppShell.jsx';
import { SkeletonCards } from './components/ui/Feedback.jsx';
import { AuthCallbackPage, LoginPage, RegisterPage } from './features/auth/AuthPages.jsx';
import { ProjectsPage } from './features/projects/ProjectsPage.jsx';
import { ProjectLayout } from './features/projects/ProjectLayout.jsx';
import { BoardPage } from './features/board/BoardPage.jsx';
import { TaskRoute } from './features/task/TaskDetail.jsx';
import { NotFoundPage } from './features/NotFoundPage.jsx';

const AskPage = lazy(() => import('./features/ask/AskPage.jsx'));
const WorkloadPage = lazy(() => import('./features/workload/WorkloadPage.jsx'));
const AnalyticsPage = lazy(() => import('./features/analytics/AnalyticsPage.jsx'));
const SettingsPage = lazy(() => import('./features/settings/SettingsPage.jsx'));
const ApiKeysPage = lazy(() => import('./features/apikeys/ApiKeysPage.jsx'));
const ProfilePage = lazy(() => import('./features/profile/ProfilePage.jsx'));
const AdminUsersPage = lazy(() => import('./features/admin/AdminUsersPage.jsx'));
const AdminMlPage = lazy(() => import('./features/admin/AdminMlPage.jsx'));

const Lazy = ({ children }) => <Suspense fallback={<SkeletonCards count={2} />}>{children}</Suspense>;

/** Route guards are UX only; the server authorises every request (§3.2). */
function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'checking') return <BootScreen />;
  if (status !== 'signedIn') return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}

function PublicOnly() {
  const { status } = useAuth();
  if (status === 'checking') return <BootScreen />;
  if (status === 'signedIn') return <Navigate to="/projects" replace />;
  return <Outlet />;
}

function RequireAdmin() {
  const { user } = useAuth();
  return user?.role === 'ADMIN' ? <Outlet /> : <NotFoundPage />;
}

function BootScreen() {
  return (
    <div className="boot" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span className="visually-hidden">Loading your session…</span>
    </div>
  );
}

const router = createBrowserRouter([
  { path: '/auth/callback', element: <AuthCallbackPage /> },
  {
    element: <PublicOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/', element: <Navigate to="/projects" replace /> },
          { path: '/projects', element: <ProjectsPage /> },
          {
            path: '/projects/:key',
            element: <ProjectLayout />,
            children: [
              { index: true, element: <Navigate to="board" replace /> },
              { path: 'board', element: <BoardPage /> },
              { path: 'tasks/:taskKey', element: <TaskRoute /> },
              {
                path: 'ask',
                element: (
                  <Lazy>
                    <AskPage />
                  </Lazy>
                ),
              },
              {
                path: 'workload',
                element: (
                  <Lazy>
                    <WorkloadPage />
                  </Lazy>
                ),
              },
              {
                path: 'analytics',
                element: (
                  <Lazy>
                    <AnalyticsPage />
                  </Lazy>
                ),
              },
              {
                path: 'settings',
                element: (
                  <Lazy>
                    <SettingsPage />
                  </Lazy>
                ),
              },
            ],
          },
          {
            path: '/api-keys',
            element: (
              <Lazy>
                <ApiKeysPage />
              </Lazy>
            ),
          },
          {
            path: '/profile',
            element: (
              <Lazy>
                <ProfilePage />
              </Lazy>
            ),
          },
          {
            element: <RequireAdmin />,
            children: [
              {
                path: '/admin/users',
                element: (
                  <Lazy>
                    <AdminUsersPage />
                  </Lazy>
                ),
              },
              {
                path: '/admin/ml',
                element: (
                  <Lazy>
                    <AdminMlPage />
                  </Lazy>
                ),
              },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage standalone /> },
]);

export function App() {
  return <RouterProvider router={router} />;
}
