import { createContext, useContext } from 'react';
import { NavLink, Outlet, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Archive, BarChart3, Gauge, KanbanSquare, MessagesSquare, Settings } from 'lucide-react';
import { projects } from '../../api/endpoints.js';
import { ErrorNotice, Skeleton } from '../../components/ui/Feedback.jsx';
import { NotFoundPage } from '../NotFoundPage.jsx';

export const ProjectContext = createContext(null);
// eslint-disable-next-line react-refresh/only-export-components
export const useProject = () => useContext(ProjectContext);

const TABS = [
  { to: 'board', label: 'Board', icon: KanbanSquare },
  { to: 'ask', label: 'Ask', icon: MessagesSquare },
  { to: 'workload', label: 'Workload', icon: Gauge },
  { to: 'analytics', label: 'Analytics', icon: BarChart3 },
  { to: 'settings', label: 'Settings', icon: Settings },
];

/** Loads the project once for every project page and renders the glass project tab bar. */
export function ProjectLayout() {
  const { key } = useParams();
  const q = useQuery({ queryKey: ['project', key], queryFn: () => projects.get(key) });
  if (q.isError && q.error?.response?.status === 404) return <NotFoundPage what="project" />;
  if (q.isError)
    return <ErrorNotice error={q.error} onRetry={q.refetch} title="Couldn't load this project" />;
  const project = q.data;

  return (
    <ProjectContext.Provider value={project}>
      <div className="page">
        <header className="project-head glass">
          <div className="project-head__title">
            {project ? (
              <>
                <h1>{project.name}</h1>
                <span className="mono muted">{project.key}</span>
                {project.status === 'ARCHIVED' && (
                  <span className="chip">
                    <Archive aria-hidden="true" /> Archived · read-only
                  </span>
                )}
              </>
            ) : (
              <Skeleton width={260} height={36} />
            )}
          </div>
          <nav aria-label="Project" className="project-tabs clay-inset">
            {TABS.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to}>
                <Icon aria-hidden="true" />
                {label}
              </NavLink>
            ))}
          </nav>
        </header>
        {project && <Outlet />}
      </div>
    </ProjectContext.Provider>
  );
}
