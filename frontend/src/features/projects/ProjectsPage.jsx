import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, CalendarClock, ListTodo, Plus } from 'lucide-react';
import { problem } from '../../api/client.js';
import { projects } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button } from '../../components/ui/Button.jsx';
import { Avatar } from '../../components/ui/Chips.jsx';
import { Dialog } from '../../components/ui/Dialog.jsx';
import { EmptyState, ErrorNotice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Textarea, fieldErrors } from '../../components/ui/Field.jsx';
import { Segmented, Bar } from '../../components/ui/Segmented.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { formatDate } from '../../utils/format.js';

export function ProjectsPage() {
  useDocumentTitle('Projects');
  const { user } = useAuth();
  const canCreate = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  const [status, setStatus] = useState('ACTIVE');
  const [creating, setCreating] = useState(false);
  const q = useQuery({ queryKey: ['projects', status], queryFn: () => projects.list(status) });
  const list = q.data || [];
  const totals = list.reduce(
    (t, p) => ({ open: t.open + p.openTasks, risk: t.risk + p.atRisk, week: t.week + p.dueThisWeek }),
    { open: 0, risk: 0, week: 0 },
  );

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>Projects</h1>
          <p>Risk at a glance across everything you&apos;re part of.</p>
        </div>
        <div className="row">
          <Segmented
            label="Project status"
            value={status}
            onChange={setStatus}
            options={[
              { value: 'ACTIVE', label: 'Active' },
              { value: 'ARCHIVED', label: 'Archived' },
            ]}
          />
          {canCreate && (
            <Button variant="primary" icon={<Plus />} onClick={() => setCreating(true)}>
              New project
            </Button>
          )}
        </div>
      </div>

      {status === 'ACTIVE' && list.length > 0 && (
        <section className="stats" aria-label="Summary">
          <Stat icon={<ListTodo />} value={totals.open} label="Open tasks" />
          <Stat
            icon={<AlertTriangle />}
            value={totals.risk}
            label="High risk"
            tone={totals.risk ? 'high' : undefined}
          />
          <Stat icon={<CalendarClock />} value={totals.week} label="Due this week" />
        </section>
      )}

      {q.isPending && (
        <div className="project-grid" aria-busy="true">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} height={188} radius="var(--radius-lg)" />
          ))}
        </div>
      )}
      {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} title="Couldn't load projects" />}
      {q.isSuccess && list.length === 0 && (
        <div className="glass panel">
          {status === 'ARCHIVED' ? (
            <EmptyState title="No archived projects">
              Archived projects appear here and stay read-only.
            </EmptyState>
          ) : canCreate ? (
            <EmptyState
              title="No projects yet"
              action={
                <Button variant="primary" icon={<Plus />} onClick={() => setCreating(true)}>
                  New project
                </Button>
              }
            >
              Create your first project to start tracking work.
            </EmptyState>
          ) : (
            <EmptyState title="No projects yet">
              You&apos;re not in any projects yet. Ask a manager to add you.
            </EmptyState>
          )}
        </div>
      )}
      {list.length > 0 && (
        <ul className="project-grid">
          {list.map((p) => (
            <li key={p.id}>
              <ProjectCard p={p} />
            </li>
          ))}
        </ul>
      )}
      <NewProjectDialog open={creating} onOpenChange={setCreating} />
    </div>
  );
}

function Stat({ icon, value, label, tone }) {
  return (
    <div className={`stat clay ${tone ? `stat--${tone}` : ''}`}>
      <span className="stat__icon" aria-hidden="true">
        {icon}
      </span>
      <span className="stat__value">{value}</span>
      <span className="stat__label">{label}</span>
    </div>
  );
}

function ProjectCard({ p }) {
  const progress = p.totalTasks ? Math.round((p.doneTasks / p.totalTasks) * 100) : 0;
  return (
    <Link to={`/projects/${p.key}/board`} className="project-card clay clay--interactive">
      <div className="row row--between">
        <span className="mono muted">{p.key}</span>
        {p.atRisk > 0 && (
          <span className="chip chip--high">
            <AlertTriangle aria-hidden="true" /> {p.atRisk} high risk
          </span>
        )}
      </div>
      <h2 className="project-card__name">{p.name}</h2>
      {p.description && <p className="project-card__desc small muted">{p.description}</p>}
      <div className="project-card__meta small">
        <span>
          <strong>{p.openTasks}</strong> open
        </span>
        <span>{p.dueThisWeek} due this week</span>
        {p.targetDate && <span>Target {formatDate(p.targetDate)}</span>}
      </div>
      <Bar value={progress} label={`${progress}% of tasks done`} />
      <div className="row row--between small">
        <span className="avatars">
          {p.memberNames.map((n) => (
            <Avatar key={n} name={n} />
          ))}
          <span className="visually-hidden">{p.memberCount} members</span>
        </span>
        <span className="muted">{progress}% done</span>
      </div>
    </Link>
  );
}

function NewProjectDialog({ open, onOpenChange }) {
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const empty = { key: '', name: '', description: '', startDate: '', targetDate: '' };
  const [v, setV] = useState(empty);
  const [errors, setErrors] = useState({});
  const m = useMutation({
    mutationFn: (body) => projects.create(body),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: ['projects'] });
      toast.success(`Project ${p.key} created`);
      onOpenChange(false);
      setV(empty);
      navigate(`/projects/${p.key}/board`);
    },
    onError: (e) => {
      const p = problem(e);
      setErrors(
        p.code === 'KEY_TAKEN'
          ? { key: 'That key is already used' }
          : { ...fieldErrors(p), _form: p.errors.length ? null : p.detail },
      );
    },
  });
  const submit = (e) => {
    e.preventDefault();
    const errs = {};
    if (!/^[A-Z]{2,6}$/.test(v.key)) errs.key = 'Use 2–6 uppercase letters, e.g. PULSE';
    if (v.name.trim().length < 3) errs.name = 'Name needs at least 3 characters';
    if (v.startDate && v.targetDate && v.targetDate < v.startDate)
      errs.targetDate = 'Target must be on or after the start date';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    m.mutate({
      key: v.key,
      name: v.name.trim(),
      description: v.description || null,
      startDate: v.startDate || null,
      targetDate: v.targetDate || null,
    });
  };
  const set =
    (k, f = (x) => x) =>
    (e) =>
      setV({ ...v, [k]: f(e.target.value) });
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="New project"
      description="The key prefixes every task, like PULSE-42. It can't change later."
      busy={m.isPending}
    >
      <form className="grid-form" onSubmit={submit} noValidate>
        {errors._form && <p className="span-2 field__error">{errors._form}</p>}
        <Field label="Key" error={errors.key} help="2–6 capital letters">
          {(p) => (
            <Input
              {...p}
              value={v.key}
              maxLength={6}
              onChange={set('key', (x) => x.toUpperCase().replace(/[^A-Z]/g, ''))}
              autoFocus
            />
          )}
        </Field>
        <Field label="Name" error={errors.name}>
          {(p) => <Input {...p} value={v.name} maxLength={80} onChange={set('name')} />}
        </Field>
        <Field label="Description" className="span-2" error={errors.description}>
          {(p) => (
            <Textarea {...p} value={v.description} maxLength={2000} onChange={set('description')} rows={3} />
          )}
        </Field>
        <Field label="Start date" error={errors.startDate}>
          {(p) => <Input {...p} type="date" value={v.startDate} onChange={set('startDate')} />}
        </Field>
        <Field label="Target date" error={errors.targetDate}>
          {(p) => <Input {...p} type="date" value={v.targetDate} onChange={set('targetDate')} />}
        </Field>
        <div className="dialog__actions span-2">
          <Button onClick={() => onOpenChange(false)} disabled={m.isPending} offlineOk>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={m.isPending}>
            Create project
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
