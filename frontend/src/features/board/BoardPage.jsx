import { useCallback, useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { AlertTriangle, Plus, Search, X } from 'lucide-react';
import { projects } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button } from '../../components/ui/Button.jsx';
import { FilterChip, PRIORITIES, priorityLabel, statusLabel } from '../../components/ui/Chips.jsx';
import { Dialog } from '../../components/ui/Dialog.jsx';
import { EmptyState, ErrorNotice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Select } from '../../components/ui/Field.jsx';
import { Segmented } from '../../components/ui/Segmented.jsx';
import { useDebounced, useDocumentTitle, useMediaQuery } from '../../hooks/misc.js';
import { hours } from '../../utils/format.js';
import { useProject } from '../projects/ProjectLayout.jsx';
import { NewTaskDialog } from './NewTaskDialog.jsx';
import { TaskCard } from './TaskCard.jsx';
import { COLUMNS, useColumn, useMoveTask } from './useBoard.js';

const NO_FILTERS = { assigneeId: '', priorities: [], highRisk: false, label: '', q: '' };

export function BoardPage() {
  const project = useProject();
  useDocumentTitle('Board', project.key);
  const { user } = useAuth();
  const readOnly = project.status === 'ARCHIVED';
  const mobile = useMediaQuery('(max-width: 639px)');
  const [mobileColumn, setMobileColumn] = useState('TODO');
  const [filters, setFilters] = useState(NO_FILTERS);
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounced(search, 300);
  const effective = { ...filters, q: debouncedSearch };
  const [creating, setCreating] = useState(false);
  const [finishing, setFinishing] = useState(null);
  const move = useMoveTask(project.key);
  const columns = {
    TODO: useColumn(project.key, 'TODO', effective),
    IN_PROGRESS: useColumn(project.key, 'IN_PROGRESS', effective),
    DONE: useColumn(project.key, 'DONE', effective),
  };
  const members = useQuery({
    queryKey: ['members', project.key],
    queryFn: () => projects.members(project.key),
  });
  const labels = useQuery({ queryKey: ['labels', project.key], queryFn: () => projects.labels(project.key) });
  const filtered = JSON.stringify(effective) !== JSON.stringify(NO_FILTERS);

  const requestMove = useCallback(
    (task, status) => {
      if (status === task.status) return;
      if (status === 'DONE')
        setFinishing({ task, actual: task.estimateHours ? String(task.estimateHours) : '' });
      else move.mutate({ task, status });
    },
    [move],
  );

  // "N" opens the new-task dialog when focus isn't in a text field (§4.7).
  useEffect(() => {
    if (readOnly) return undefined;
    const onKey = (e) => {
      const t = e.target;
      const typing = t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName);
      if (!typing && !e.metaKey && !e.ctrlKey && !e.altKey && e.key.toLowerCase() === 'n') {
        e.preventDefault();
        setCreating(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [readOnly]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor),
  );
  const onDragEnd = ({ active, over }) => {
    if (over && active.data.current?.task) requestMove(active.data.current.task, over.id);
  };
  const announcements = {
    onDragStart: ({ active }) => `Picked up ${active.id}. Use arrow keys to choose a column, Space to drop.`,
    onDragOver: ({ active, over }) =>
      over ? `${active.id} is over ${statusLabel(over.id)}` : `${active.id} is not over a column`,
    onDragEnd: ({ active, over }) =>
      over ? `${active.id} dropped on ${statusLabel(over.id)}` : `${active.id} dropped`,
    onDragCancel: ({ active }) => `Moving ${active.id} was cancelled`,
  };

  const allEmpty = COLUMNS.every((c) => columns[c].isSuccess && columns[c].data.totalItems === 0);
  const memberList = members.data || [];
  const assigneeOptions =
    user.role === 'MEMBER' ? memberList.filter((m) => m.userId === user.id) : memberList;

  return (
    <section className="board" aria-label="Task board">
      <div className="board__toolbar glass">
        <div className="board__filters">
          <label className="board__search">
            <Search aria-hidden="true" />
            <span className="visually-hidden">Search tasks</span>
            <input
              className="input"
              type="search"
              placeholder="Search tasks"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              maxLength={100}
            />
          </label>
          <label className="visually-hidden" htmlFor="filter-assignee">
            Assignee
          </label>
          <Select
            id="filter-assignee"
            value={filters.assigneeId}
            onChange={(e) => setFilters({ ...filters, assigneeId: e.target.value })}
          >
            <option value="">Anyone</option>
            <option value="unassigned">Unassigned</option>
            {memberList.map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.name}
              </option>
            ))}
          </Select>
          <label className="visually-hidden" htmlFor="filter-label">
            Label
          </label>
          <Select
            id="filter-label"
            value={filters.label}
            onChange={(e) => setFilters({ ...filters, label: e.target.value })}
          >
            <option value="">Any label</option>
            {(labels.data || []).map((l) => (
              <option key={l.id} value={l.name}>
                {l.name}
              </option>
            ))}
          </Select>
          <div className="row" role="group" aria-label="Priority">
            {PRIORITIES.slice()
              .reverse()
              .map((p) => (
                <FilterChip
                  key={p}
                  pressed={filters.priorities.includes(p)}
                  onToggle={() =>
                    setFilters({
                      ...filters,
                      priorities: filters.priorities.includes(p)
                        ? filters.priorities.filter((x) => x !== p)
                        : [...filters.priorities, p],
                    })
                  }
                >
                  {priorityLabel(p)}
                </FilterChip>
              ))}
            <FilterChip
              pressed={filters.highRisk}
              onToggle={() => setFilters({ ...filters, highRisk: !filters.highRisk })}
            >
              <AlertTriangle aria-hidden="true" /> High risk
            </FilterChip>
          </div>
          {filtered && (
            <Button
              variant="ghost"
              size="sm"
              icon={<X />}
              offlineOk
              onClick={() => {
                setFilters(NO_FILTERS);
                setSearch('');
              }}
            >
              Clear filters
            </Button>
          )}
        </div>
        {!readOnly && (
          <Button variant="primary" icon={<Plus />} onClick={() => setCreating(true)} aria-keyshortcuts="N">
            New task
          </Button>
        )}
      </div>

      {mobile && (
        <Segmented
          className="board__switch"
          label="Column"
          value={mobileColumn}
          onChange={setMobileColumn}
          options={COLUMNS.map((c) => ({
            value: c,
            label: `${statusLabel(c)} ${columns[c].data?.totalItems ?? ''}`,
          }))}
        />
      )}

      {allEmpty && !filtered ? (
        <div className="glass panel">
          <EmptyState
            title="This board is empty"
            action={
              !readOnly && (
                <Button variant="primary" icon={<Plus />} onClick={() => setCreating(true)}>
                  New task
                </Button>
              )
            }
          >
            Capture the first piece of work. Press N any time to add a task.
          </EmptyState>
        </div>
      ) : (
        <DndContext sensors={sensors} onDragEnd={onDragEnd} accessibility={{ announcements }}>
          <div className="board__columns" onKeyDown={navigateCards}>
            {COLUMNS.filter((c) => !mobile || c === mobileColumn).map((status) => (
              <Column
                key={status}
                status={status}
                query={columns[status]}
                projectKey={project.key}
                readOnly={readOnly}
                filtered={filtered}
                onMove={requestMove}
                onNew={() => setCreating(true)}
              />
            ))}
          </div>
        </DndContext>
      )}

      {creating && (
        <NewTaskDialog
          open={creating}
          onOpenChange={setCreating}
          project={project}
          members={assigneeOptions}
          labels={labels.data || []}
        />
      )}
      <FinishDialog
        state={finishing}
        busy={move.isPending}
        onChange={setFinishing}
        onConfirm={() => {
          move.mutate({
            task: finishing.task,
            status: 'DONE',
            actualHours: finishing.actual ? Number(finishing.actual) : undefined,
          });
          setFinishing(null);
        }}
      />
    </section>
  );
}

/** Arrow keys move focus between cards; Left/Right jump to the neighbouring column (§4.7). */
function navigateCards(e) {
  const el = e.target;
  if (!el.dataset?.card || !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return;
  const cols = COLUMNS.map((c) => [...document.querySelectorAll(`[data-card][data-col="${c}"]`)]).filter(
    (l) => l.length,
  );
  const ci = cols.findIndex((l) => l.includes(el));
  const ri = cols[ci].indexOf(el);
  let target;
  if (e.key === 'ArrowDown') target = cols[ci][ri + 1];
  if (e.key === 'ArrowUp') target = cols[ci][ri - 1];
  if (e.key === 'ArrowRight') target = cols[ci + 1]?.[Math.min(ri, cols[ci + 1].length - 1)];
  if (e.key === 'ArrowLeft') target = cols[ci - 1]?.[Math.min(ri, cols[ci - 1].length - 1)];
  if (target) {
    e.preventDefault();
    target.focus();
  }
}

function Column({ status, query, projectKey, readOnly, filtered, onMove, onNew }) {
  const { setNodeRef, isOver } = useDroppable({ id: status, disabled: readOnly });
  const items = query.data?.items || [];
  const remaining = items.reduce(
    (sum, t) => sum + Number(t.estimateHours ?? t.prediction?.predictedHours ?? 0),
    0,
  );
  const titleId = `col-${status}`;
  return (
    <section
      ref={setNodeRef}
      className={`column glass ${isOver ? 'column--over' : ''}`}
      aria-labelledby={titleId}
    >
      <header className="column__head">
        <h2 id={titleId} className="column__title">
          {statusLabel(status)} <span className="muted">· {query.data?.totalItems ?? '…'}</span>
        </h2>
        {status !== 'DONE' && remaining > 0 && <span className="small muted">{hours(remaining)} left</span>}
      </header>
      {query.isPending && (
        <div className="column__list" aria-busy="true">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} height={128} radius="var(--radius-lg)" />
          ))}
        </div>
      )}
      {query.isError && (
        <ErrorNotice error={query.error} onRetry={query.refetch} title="Couldn't load tasks" />
      )}
      {query.isSuccess && items.length === 0 && (
        <EmptyState
          title={filtered ? 'No matches' : 'Nothing here yet.'}
          icon={<span />}
          action={
            status === 'TODO' &&
            !filtered &&
            !readOnly && (
              <Button size="sm" icon={<Plus />} onClick={onNew}>
                New task
              </Button>
            )
          }
        >
          {filtered ? 'No tasks in this column match the filters.' : null}
        </EmptyState>
      )}
      {items.length > 0 && (
        <ul className="column__list">
          {items.map((t, i) => (
            <li key={t.key}>
              <TaskCard
                task={t}
                projectKey={projectKey}
                onMove={onMove}
                readOnly={readOnly}
                column={status}
                index={i}
              />
            </li>
          ))}
        </ul>
      )}
      {query.data && query.data.totalItems > items.length && (
        <p className="small muted column__more">
          Showing {items.length} of {query.data.totalItems}. Use filters to narrow down.
        </p>
      )}
    </section>
  );
}

/** Moving to Done asks for actual hours (optional, FR-023) — feeds the duration model. */
function FinishDialog({ state, onChange, onConfirm, busy }) {
  return (
    <Dialog
      open={Boolean(state)}
      onOpenChange={(o) => !o && onChange(null)}
      title={state ? `Finish ${state.task.key}` : 'Finish task'}
      description="How long did it actually take? This is optional and improves future estimates."
    >
      {state && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            onConfirm();
          }}
        >
          <Field label="Actual hours" help="0.5 to 400, in steps of 0.5">
            {(p) => (
              <Input
                {...p}
                type="number"
                min="0.5"
                max="400"
                step="0.5"
                value={state.actual}
                onChange={(e) => onChange({ ...state, actual: e.target.value })}
              />
            )}
          </Field>
          <div className="dialog__actions">
            <Button onClick={() => onChange(null)} offlineOk>
              Cancel
            </Button>
            <Button type="submit" variant="primary" loading={busy}>
              Mark done
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
