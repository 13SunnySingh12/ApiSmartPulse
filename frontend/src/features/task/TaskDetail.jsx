import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MoreHorizontal, Pencil, RefreshCw, Sparkles, Trash2, X } from 'lucide-react';
import { problem } from '../../api/client.js';
import { projects, tasks } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button, IconButton } from '../../components/ui/Button.jsx';
import {
  AiTag,
  LabelChip,
  PRIORITIES,
  PriorityChip,
  RiskBadge,
  StatusPill,
  priorityLabel,
  statusLabel,
} from '../../components/ui/Chips.jsx';
import { ConfirmDialog, Drawer } from '../../components/ui/Dialog.jsx';
import { ErrorNotice, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Select, Textarea, fieldErrors } from '../../components/ui/Field.jsx';
import { Markdown } from '../../components/ui/Markdown.jsx';
import { Menu, MenuItem, MenuLabel, MenuSeparator } from '../../components/ui/Menu.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { formatDate, formatDateTime, hours, isOverdue } from '../../utils/format.js';
import { BoardPage } from '../board/BoardPage.jsx';
import { COLUMNS, useMoveTask } from '../board/useBoard.js';
import { useProject } from '../projects/ProjectLayout.jsx';
import { Activity, Blockers, Comments, LabelsMenu, Subtasks } from './TaskSections.jsx';
import { useTaskMutation } from './useTaskMutation.js';

/** /projects/:key/tasks/:taskKey — the board stays underneath; the detail is a drawer (§4.7 P-05). */
export function TaskRoute() {
  const { taskKey } = useParams();
  const project = useProject();
  const navigate = useNavigate();
  const close = () => navigate(`/projects/${project.key}/board`);
  return (
    <>
      <BoardPage />
      <Drawer open onOpenChange={(o) => !o && close()} title={`Task ${taskKey}`}>
        <TaskDetail taskKey={taskKey} onClose={close} />
      </Drawer>
    </>
  );
}

export function TaskDetail({ taskKey, onClose }) {
  const project = useProject();
  const q = useQuery({ queryKey: ['task', taskKey], queryFn: () => tasks.get(taskKey) });
  useDocumentTitle(taskKey, project.key);

  if (q.isPending) {
    return (
      <div className="detail" aria-busy="true">
        <Skeleton height={28} width="40%" />
        <Skeleton height={40} />
        <Skeleton height={160} />
        <Skeleton height={120} />
      </div>
    );
  }
  if (q.isError) {
    const notFound = q.error?.response?.status === 404;
    return (
      <div className="detail">
        <DetailTop taskKey={taskKey} onClose={onClose} />
        {notFound ? (
          <Notice kind="warning" title="Task not found">
            It may have been deleted, or the key is wrong.
          </Notice>
        ) : (
          <ErrorNotice error={q.error} onRetry={q.refetch} />
        )}
      </div>
    );
  }
  return <TaskView task={q.data} onClose={onClose} />;
}

function DetailTop({ taskKey, onClose, children }) {
  return (
    <div className="detail__top">
      <div className="row">
        <span className="mono">{taskKey}</span>
        {children}
      </div>
      <IconButton label="Close task" onClick={onClose}>
        <X />
      </IconButton>
    </div>
  );
}

function TaskView({ task, onClose }) {
  const project = useProject();
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const readOnly = task.projectArchived;
  const [editing, setEditing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const move = useMoveTask(project.key);
  const members = useQuery({
    queryKey: ['members', project.key],
    queryFn: () => projects.members(project.key),
  });

  const remove = useMutation({
    mutationFn: () => tasks.remove(task.key),
    onSuccess: () => {
      toast.success(`${task.key} deleted`);
      qc.invalidateQueries({ queryKey: ['tasks', project.key] });
      qc.removeQueries({ queryKey: ['task', task.key] });
      navigate(`/projects/${project.key}/board`);
    },
    onError: (e) => toast.error(problem(e).detail),
  });

  return (
    <article className="detail" aria-labelledby="task-title">
      <DetailTop taskKey={task.key} onClose={onClose}>
        <StatusPill status={task.status} />
        {!readOnly && (
          <Menu
            label="Task actions"
            trigger={
              <IconButton label="More actions" size="sm">
                <MoreHorizontal />
              </IconButton>
            }
          >
            <MenuLabel>Move to…</MenuLabel>
            {COLUMNS.filter((c) => c !== task.status).map((c) => (
              <MenuItem key={c} onSelect={() => move.mutate({ task, status: c })}>
                {statusLabel(c)}
              </MenuItem>
            ))}
            <MenuSeparator />
            <MenuItem icon={<Pencil />} onSelect={() => setEditing(true)}>
              Edit details
            </MenuItem>
            {task.canDelete && (
              <MenuItem icon={<Trash2 />} onSelect={() => setConfirmDelete(true)}>
                Delete task
              </MenuItem>
            )}
          </Menu>
        )}
      </DetailTop>

      <h2 id="task-title" className="detail__title">
        {task.title}
      </h2>
      <div className="row">
        <PriorityChip priority={task.priority} />
        {task.status !== 'DONE' && <RiskBadge prediction={task.prediction} showPct />}
        {task.labels.map((l) => (
          <LabelChip key={l.id} label={l} />
        ))}
        {!readOnly && <LabelsMenu task={task} />}
      </div>
      {readOnly && <Notice kind="info">This project is archived, so this task is read-only.</Notice>}

      {task.status !== 'DONE' && <Suggestions task={task} readOnly={readOnly} />}

      {editing ? (
        <DetailsForm task={task} members={members.data || []} user={user} onDone={() => setEditing(false)} />
      ) : (
        <section className="detail__section" aria-label="Details">
          <dl className="facts">
            <div>
              <dt>Assignee</dt>
              <dd>{task.assignee?.name || 'Unassigned'}</dd>
            </div>
            <div>
              <dt>Due</dt>
              <dd className={isOverdue(task.dueDate, task.status) ? 'due--overdue' : undefined}>
                {task.dueDate ? formatDate(task.dueDate) : 'No due date'}
                {isOverdue(task.dueDate, task.status) && ' (overdue)'}
              </dd>
            </div>
            <div>
              <dt>Estimate</dt>
              <dd>{task.estimateHours ? hours(task.estimateHours) : 'Not estimated'}</dd>
            </div>
            <div>
              <dt>Prediction</dt>
              <dd className="row">
                {task.prediction.status === 'OK' ? (
                  <>
                    {hours(task.prediction.predictedHours)} <AiTag source={task.prediction.source} />
                  </>
                ) : (
                  <span className="muted">Pending</span>
                )}
              </dd>
            </div>
            {task.actualHours && (
              <div>
                <dt>Actual</dt>
                <dd>{hours(task.actualHours)}</dd>
              </div>
            )}
            <div>
              <dt>Created by</dt>
              <dd>{task.creator.name}</dd>
            </div>
          </dl>
          <div className="row row--between">
            <h3>Description</h3>
            {!readOnly && (
              <Button size="sm" variant="ghost" icon={<Pencil />} onClick={() => setEditing(true)}>
                Edit
              </Button>
            )}
          </div>
          {task.description ? (
            <Markdown>{task.description}</Markdown>
          ) : (
            <p className="muted">No description yet.</p>
          )}
        </section>
      )}

      <Subtasks task={task} readOnly={readOnly} />
      <Blockers task={task} readOnly={readOnly} />
      <Comments task={task} readOnly={readOnly} />
      <Activity task={task} />
      <p className="small muted">
        Created {formatDateTime(task.createdAt)} · Updated {formatDateTime(task.updatedAt)}
      </p>

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={`Delete ${task.key}?`}
        description={`"${task.title}" and its subtasks, labels and comments will be removed. This can't be undone.`}
        confirmLabel="Delete task"
        busy={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </article>
  );
}

/** Advisory AI values (BR-012, DS-005): nothing changes until the user presses Apply. */
function Suggestions({ task, readOnly }) {
  const toast = useToast();
  const [dismissed, setDismissed] = useState({});
  const p = task.prediction;
  const apply = useTaskMutation(task, {
    mutationFn: (body) => tasks.update(task.key, { ...body, version: task.version }),
    onSuccess: () => toast.success('Suggestion applied'),
    onError: (e) => toast.error(problem(e).detail),
  });
  const refresh = useTaskMutation(task, {
    mutationFn: () => tasks.refreshPrediction(task.key),
  });

  if (p.status !== 'OK') {
    return (
      <Notice
        kind="info"
        title="Prediction pending"
        actions={
          <Button size="sm" icon={<RefreshCw />} loading={refresh.isPending} onClick={() => refresh.mutate()}>
            Try again
          </Button>
        }
      >
        Estimates and risk appear once the AI service responds. Your task is saved either way.
      </Notice>
    );
  }
  const priorityDiffers = p.suggestedPriority && p.suggestedPriority !== task.priority && !dismissed.priority;
  const estimateDiffers =
    p.predictedHours != null &&
    Number(p.predictedHours) !== Number(task.estimateHours) &&
    !dismissed.estimate;
  if (!priorityDiffers && !estimateDiffers) return null;
  return (
    <div className="stack">
      {priorityDiffers && (
        <div className="ai-card clay">
          <Sparkles className="ai-card__icon" aria-hidden="true" />
          <div className="ai-card__body">
            <p>
              Suggested priority: <strong>{priorityLabel(p.suggestedPriority)}</strong>{' '}
              <span className="muted">(confidence {Number(p.priorityConfidence).toFixed(2)})</span>{' '}
              <AiTag source={p.source} />
            </p>
            {!readOnly && (
              <div className="row">
                <Button
                  size="sm"
                  variant="primary"
                  loading={apply.isPending}
                  onClick={() =>
                    apply.mutate({ priority: p.suggestedPriority, appliedSuggestion: 'PRIORITY' })
                  }
                >
                  Apply
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  offlineOk
                  onClick={() => setDismissed({ ...dismissed, priority: true })}
                >
                  Dismiss
                </Button>
              </div>
            )}
          </div>
        </div>
      )}
      {estimateDiffers && (
        <div className="ai-card clay">
          <Sparkles className="ai-card__icon" aria-hidden="true" />
          <div className="ai-card__body">
            <p>
              {task.estimateHours ? 'Estimated' : 'Suggested estimate'}:{' '}
              <strong>{hours(p.predictedHours)}</strong>
              {task.estimateHours && (
                <span className="muted"> (yours: {hours(task.estimateHours)})</span>
              )}{' '}
              <AiTag source={p.source} />
            </p>
            {!readOnly && (
              <div className="row">
                <Button
                  size="sm"
                  variant="primary"
                  loading={apply.isPending}
                  onClick={() =>
                    apply.mutate({ estimateHours: Number(p.predictedHours), appliedSuggestion: 'ESTIMATE' })
                  }
                >
                  Apply
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  offlineOk
                  onClick={() => setDismissed({ ...dismissed, estimate: true })}
                >
                  Dismiss
                </Button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/** PATCH with optimistic locking. A 409 keeps the user's text and offers Reload (QA-FE-05). */
function DetailsForm({ task, members, user, onDone }) {
  const toast = useToast();
  const qc = useQueryClient();
  const initial = {
    title: task.title,
    description: task.description || '',
    priority: task.priority,
    dueDate: task.dueDate || '',
    assigneeId: task.assignee?.id ? String(task.assignee.id) : '',
    estimateHours: task.estimateHours ?? '',
    actualHours: task.actualHours ?? '',
  };
  const [v, setV] = useState(initial);
  const [errors, setErrors] = useState({});
  const [conflict, setConflict] = useState(false);
  const save = useTaskMutation(task, {
    mutationFn: (body) => tasks.update(task.key, body),
    onSuccess: () => {
      toast.success('Task saved');
      onDone();
    },
    onError: (e) => {
      const p = problem(e);
      if (p.code === 'CONFLICT_STALE') setConflict(true);
      else setErrors({ ...fieldErrors(p), _form: p.errors.length ? null : p.detail });
    },
  });
  const assignable =
    user.role === 'MEMBER'
      ? members.filter((m) => m.userId === user.id || m.userId === task.assignee?.id)
      : members;

  const submit = (e) => {
    e.preventDefault();
    const body = { version: task.version };
    const num = (x) => (x === '' ? null : Number(x));
    if (v.title !== initial.title) body.title = v.title;
    if (v.description !== initial.description) body.description = v.description;
    if (v.priority !== initial.priority) body.priority = v.priority;
    if (v.dueDate !== initial.dueDate) body.dueDate = v.dueDate || null;
    if (v.assigneeId !== initial.assigneeId) body.assigneeId = v.assigneeId ? Number(v.assigneeId) : null;
    if (String(v.estimateHours) !== String(initial.estimateHours)) body.estimateHours = num(v.estimateHours);
    if (String(v.actualHours) !== String(initial.actualHours)) body.actualHours = num(v.actualHours);
    if (Object.keys(body).length === 1) return onDone();
    if (v.title.trim().length < 3) return setErrors({ title: 'Title needs 3–200 characters' });
    setErrors({});
    save.mutate(body);
  };
  const set = (k) => (e) => setV({ ...v, [k]: e.target.value });

  return (
    <form className="detail__section grid-form" onSubmit={submit} noValidate aria-label="Edit details">
      {conflict && (
        <Notice
          kind="warning"
          title="This task changed"
          actions={
            <Button
              size="sm"
              icon={<RefreshCw />}
              onClick={async () => {
                await qc.invalidateQueries({ queryKey: ['task', task.key] });
                setConflict(false);
              }}
            >
              Reload latest
            </Button>
          }
        >
          Someone saved it after you opened it. Reload to see the latest version; your edits stay in the form.
        </Notice>
      )}
      {errors._form && <p className="span-2 field__error">{errors._form}</p>}
      <Field label="Title" className="span-2" error={errors.title}>
        {(p) => <Input {...p} value={v.title} maxLength={200} onChange={set('title')} />}
      </Field>
      <Field label="Description" className="span-2" help="Markdown supported" error={errors.description}>
        {(p) => (
          <Textarea {...p} value={v.description} maxLength={10000} rows={6} onChange={set('description')} />
        )}
      </Field>
      <Field label="Priority">
        {(p) => (
          <Select {...p} value={v.priority} onChange={set('priority')}>
            {PRIORITIES.map((x) => (
              <option key={x} value={x}>
                {priorityLabel(x)}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label="Due date" error={errors.dueDate}>
        {(p) => <Input {...p} type="date" value={v.dueDate} onChange={set('dueDate')} />}
      </Field>
      <Field label="Assignee" error={errors.assigneeId}>
        {(p) => (
          <Select {...p} value={v.assigneeId} onChange={set('assigneeId')}>
            <option value="">Unassigned</option>
            {assignable.map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label="Estimate (hours)" error={errors.estimateHours}>
        {(p) => (
          <Input
            {...p}
            type="number"
            min="0.5"
            max="400"
            step="0.5"
            value={v.estimateHours}
            onChange={set('estimateHours')}
          />
        )}
      </Field>
      {task.status === 'DONE' && (
        <Field label="Actual hours" error={errors.actualHours}>
          {(p) => (
            <Input
              {...p}
              type="number"
              min="0.5"
              max="400"
              step="0.5"
              value={v.actualHours}
              onChange={set('actualHours')}
            />
          )}
        </Field>
      )}
      <div className="dialog__actions span-2">
        <Button onClick={onDone} disabled={save.isPending} offlineOk>
          Cancel
        </Button>
        <Button type="submit" variant="primary" loading={save.isPending}>
          Save
        </Button>
      </div>
    </form>
  );
}
