import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as DM from '@radix-ui/react-dropdown-menu';
import { ArrowDown, ArrowUp, Check, Pencil, Plus, Sparkles, Tag, Trash2, X } from 'lucide-react';
import { problem } from '../../api/client.js';
import { ai, projects, tasks } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button, IconButton } from '../../components/ui/Button.jsx';
import { StatusPill, statusLabel } from '../../components/ui/Chips.jsx';
import { Dialog } from '../../components/ui/Dialog.jsx';
import { ErrorNotice, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Textarea } from '../../components/ui/Field.jsx';
import { Markdown } from '../../components/ui/Markdown.jsx';
import { Bar } from '../../components/ui/Segmented.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { formatDateTime } from '../../utils/format.js';
import { useProject } from '../projects/ProjectLayout.jsx';
import { useTaskMutation } from './useTaskMutation.js';

const SPLIT_MIN_HOURS = Number(import.meta.env.VITE_SPLIT_MIN_HOURS || 16);

// ── Subtasks (FR-026) and auto-split (FR-064) ─────────────────────────────────

export function Subtasks({ task, readOnly }) {
  const toast = useToast();
  const [title, setTitle] = useState('');
  const [editing, setEditing] = useState(null);
  const [splitOpen, setSplitOpen] = useState(false);
  const fail = (e) => toast.error(problem(e).detail);
  const add = useTaskMutation(task, {
    mutationFn: () => tasks.addSubtask(task.key, title.trim()),
    onSuccess: () => setTitle(''),
    onError: fail,
  });
  const update = useTaskMutation(task, {
    mutationFn: ({ id, body }) => tasks.updateSubtask(id, body),
    onSuccess: () => setEditing(null),
    onError: fail,
  });
  const remove = useTaskMutation(task, { mutationFn: (id) => tasks.deleteSubtask(id), onError: fail });
  const reorder = useTaskMutation(task, {
    mutationFn: (ids) => tasks.reorderSubtasks(task.key, ids),
    onError: fail,
  });
  const { done, total } = task.subtaskSummary;
  const size = Number(task.estimateHours ?? task.prediction?.predictedHours ?? 0);
  const canSplit = !readOnly && size >= SPLIT_MIN_HOURS && task.status !== 'DONE';

  const swap = (i, j) => {
    const ids = task.subtasks.map((s) => s.id);
    [ids[i], ids[j]] = [ids[j], ids[i]];
    reorder.mutate(ids);
  };

  return (
    <section className="detail__section" aria-labelledby="subtasks-h">
      <div className="row row--between">
        <h3 id="subtasks-h">
          Subtasks{' '}
          <span className="muted small">
            {done}/{total}
          </span>
        </h3>
        {canSplit && (
          <Button size="sm" icon={<Sparkles />} onClick={() => setSplitOpen(true)}>
            Split this task
          </Button>
        )}
      </div>
      {total > 0 && <Bar value={(done / total) * 100} label={`${done} of ${total} subtasks done`} />}
      <ul className="subtasks">
        {task.subtasks.map((s, i) => (
          <li key={s.id} className="subtask">
            {editing?.id === s.id ? (
              <form
                className="row subtask__edit"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (editing.title.trim())
                    update.mutate({ id: s.id, body: { title: editing.title.trim() } });
                }}
              >
                <label className="visually-hidden" htmlFor={`st-${s.id}`}>
                  Subtask title
                </label>
                <Input
                  id={`st-${s.id}`}
                  value={editing.title}
                  maxLength={120}
                  autoFocus
                  onChange={(e) => setEditing({ ...editing, title: e.target.value })}
                />
                <IconButton label="Save subtask" size="sm" type="submit" loading={update.isPending}>
                  <Check />
                </IconButton>
                <IconButton label="Cancel editing" size="sm" variant="ghost" onClick={() => setEditing(null)}>
                  <X />
                </IconButton>
              </form>
            ) : (
              <>
                <label className="checkbox subtask__check">
                  <input
                    type="checkbox"
                    checked={s.done}
                    disabled={readOnly}
                    onChange={(e) => update.mutate({ id: s.id, body: { done: e.target.checked } })}
                  />
                  <span className={s.done ? 'subtask__done' : undefined}>{s.title}</span>
                </label>
                {!readOnly && (
                  <span className="subtask__tools">
                    <IconButton
                      label={`Move "${s.title}" up`}
                      size="sm"
                      variant="ghost"
                      disabled={i === 0}
                      onClick={() => swap(i, i - 1)}
                    >
                      <ArrowUp />
                    </IconButton>
                    <IconButton
                      label={`Move "${s.title}" down`}
                      size="sm"
                      variant="ghost"
                      disabled={i === task.subtasks.length - 1}
                      onClick={() => swap(i, i + 1)}
                    >
                      <ArrowDown />
                    </IconButton>
                    <IconButton
                      label={`Rename "${s.title}"`}
                      size="sm"
                      variant="ghost"
                      onClick={() => setEditing({ id: s.id, title: s.title })}
                    >
                      <Pencil />
                    </IconButton>
                    <IconButton
                      label={`Delete "${s.title}"`}
                      size="sm"
                      variant="ghost"
                      onClick={() => remove.mutate(s.id)}
                    >
                      <Trash2 />
                    </IconButton>
                  </span>
                )}
              </>
            )}
          </li>
        ))}
      </ul>
      {!readOnly && total < 50 && (
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault();
            if (title.trim()) add.mutate();
          }}
        >
          <label className="visually-hidden" htmlFor="new-subtask">
            New subtask
          </label>
          <Input
            id="new-subtask"
            placeholder="Add a subtask"
            value={title}
            maxLength={120}
            onChange={(e) => setTitle(e.target.value)}
            className="input grow"
          />
          <Button type="submit" size="sm" icon={<Plus />} loading={add.isPending} disabled={!title.trim()}>
            Add
          </Button>
        </form>
      )}
      {splitOpen && <SplitDialog task={task} onClose={() => setSplitOpen(false)} />}
    </section>
  );
}

function SplitDialog({ task, onClose }) {
  const toast = useToast();
  const remaining = 50 - task.subtaskSummary.total;
  const q = useQuery({
    queryKey: ['split', task.key],
    queryFn: () => ai.split(task.key),
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const [items, setItems] = useState(null);
  const list = items ?? (q.data?.suggestions || []).map((s) => s.title);
  const accept = useTaskMutation(task, {
    mutationFn: (titles) => tasks.bulkSubtasks(task.key, titles),
    onSuccess: (_d, titles) => {
      toast.success(`${titles.length} subtasks added`);
      onClose();
    },
    onError: (e) => toast.error(problem(e).detail),
  });
  const p = q.error && problem(q.error);
  const errorText =
    p &&
    ({
      AI_RATE_LIMITED: `AI is busy. Try again in ${p.retryAfter || 30} s.`,
      AI_BAD_OUTPUT: "Couldn't generate suggestions. Try again later.",
      AI_WARMING_UP: 'The AI model is still loading. Try again in a minute.',
    }[p.code] ||
      p.detail);
  const cleaned = list.map((t) => t.trim()).filter(Boolean);

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Split this task"
      description="AI-suggested subtasks. Edit or remove any, then add them. Nothing is saved until you do."
      busy={accept.isPending}
    >
      {q.isPending && (
        <div className="stack" aria-busy="true">
          <p className="small muted">Thinking about {task.key}…</p>
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} height={40} />
          ))}
        </div>
      )}
      {q.isError && (
        <Notice
          kind={p.code === 'AI_RATE_LIMITED' ? 'warning' : 'error'}
          title="No suggestions"
          actions={
            <Button size="sm" onClick={() => q.refetch()}>
              Try again
            </Button>
          }
        >
          {errorText}
        </Notice>
      )}
      {q.isSuccess && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            accept.mutate(cleaned.slice(0, Math.min(10, remaining)));
          }}
        >
          <ol className="split-list">
            {list.map((t, i) => (
              <li key={i} className="row">
                <label className="visually-hidden" htmlFor={`split-${i}`}>
                  Suggestion {i + 1}
                </label>
                <Input
                  id={`split-${i}`}
                  value={t}
                  maxLength={120}
                  onChange={(e) => setItems(list.map((x, j) => (j === i ? e.target.value : x)))}
                />
                <IconButton
                  label={`Remove suggestion ${i + 1}`}
                  size="sm"
                  variant="ghost"
                  onClick={() => setItems(list.filter((_, j) => j !== i))}
                >
                  <X />
                </IconButton>
              </li>
            ))}
          </ol>
          <p className="small muted">
            AI-generated from this task's title and description. Check before adding.
          </p>
          <div className="dialog__actions">
            <Button onClick={onClose} disabled={accept.isPending} offlineOk>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              loading={accept.isPending}
              disabled={cleaned.length === 0}
            >
              Add {cleaned.length} subtasks
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

// ── Labels on a task (FR-027) ─────────────────────────────────────────────────

export function LabelsMenu({ task }) {
  const project = useProject();
  const toast = useToast();
  const labels = useQuery({ queryKey: ['labels', project.key], queryFn: () => projects.labels(project.key) });
  const set = useTaskMutation(task, {
    mutationFn: (ids) => tasks.setLabels(task.key, ids),
    onError: (e) => toast.error(problem(e).detail),
  });
  const current = task.labels.map((l) => l.id);
  return (
    <DM.Root>
      <DM.Trigger asChild>
        <button type="button" className="chip filter-chip" aria-label="Edit labels">
          <Tag aria-hidden="true" /> Labels
        </button>
      </DM.Trigger>
      <DM.Portal>
        <DM.Content className="menu glass glass--raised" sideOffset={6} align="start">
          {(labels.data || []).length === 0 && (
            <p className="menu__label">No labels yet. Add them in Settings.</p>
          )}
          {(labels.data || []).map((l) => (
            <DM.CheckboxItem
              key={l.id}
              className="menu__item"
              checked={current.includes(l.id)}
              disabled={!current.includes(l.id) && current.length >= 10}
              onSelect={(e) => e.preventDefault()}
              onCheckedChange={(on) =>
                set.mutate(on ? [...current, l.id] : current.filter((x) => x !== l.id))
              }
            >
              <span className={`chip__dot dot--${l.color}`} aria-hidden="true" />
              {l.name}
              <DM.ItemIndicator className="menu__check">
                <Check aria-hidden="true" />
              </DM.ItemIndicator>
            </DM.CheckboxItem>
          ))}
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  );
}

// ── Dependencies (FR-029) ─────────────────────────────────────────────────────

export function Blockers({ task, readOnly }) {
  const project = useProject();
  const [key, setKey] = useState('');
  const [error, setError] = useState(null);
  const add = useTaskMutation(task, {
    mutationFn: () => tasks.addBlocker(task.key, key.trim().toUpperCase()),
    onSuccess: () => {
      setKey('');
      setError(null);
    },
    onError: (e) => {
      const p = problem(e);
      setError(
        p.code === 'DEPENDENCY_CYCLE'
          ? `That would create a loop: ${p.data.cycle.join(' → ')}`
          : p.errors[0]?.message
            ? `Blocker ${p.errors[0].message}`
            : p.detail,
      );
    },
  });
  const remove = useTaskMutation(task, { mutationFn: (k) => tasks.removeBlocker(task.key, k) });
  const unfinished = task.blockedBy.filter((b) => b.status !== 'DONE');

  return (
    <section className="detail__section" aria-labelledby="deps-h">
      <h3 id="deps-h">Dependencies</h3>
      {unfinished.length > 0 && task.status !== 'DONE' && (
        <Notice kind="warning" title="Blocked">
          This task can&apos;t start or finish until {unfinished.map((b) => b.key).join(', ')}{' '}
          {unfinished.length > 1 ? 'are' : 'is'} done.
        </Notice>
      )}
      <div className="stack">
        <p className="small muted">Blocked by</p>
        {task.blockedBy.length === 0 && <p className="small">Nothing blocks this task.</p>}
        <ul className="refs">
          {task.blockedBy.map((b) => (
            <li key={b.key} className="row">
              <Link to={`/projects/${project.key}/tasks/${b.key}`} className="mono">
                {b.key}
              </Link>
              <span className="grow">{b.title}</span>
              <StatusPill status={b.status} />
              {!readOnly && (
                <IconButton
                  label={`Remove blocker ${b.key}`}
                  size="sm"
                  variant="ghost"
                  onClick={() => remove.mutate(b.key)}
                >
                  <X />
                </IconButton>
              )}
            </li>
          ))}
        </ul>
        {!readOnly && (
          <form
            className="row"
            onSubmit={(e) => {
              e.preventDefault();
              if (key.trim()) add.mutate();
            }}
          >
            <Field label="Add blocker" error={error} className="grow">
              {(p) => (
                <Input
                  {...p}
                  placeholder={`${project.key}-12`}
                  value={key}
                  onChange={(e) => setKey(e.target.value)}
                />
              )}
            </Field>
            <Button type="submit" size="sm" icon={<Plus />} loading={add.isPending} className="align-end">
              Add
            </Button>
          </form>
        )}
        {task.blocks.length > 0 && (
          <>
            <p className="small muted">Blocks</p>
            <ul className="refs">
              {task.blocks.map((b) => (
                <li key={b.key} className="row">
                  <Link to={`/projects/${project.key}/tasks/${b.key}`} className="mono">
                    {b.key}
                  </Link>
                  <span className="grow">{b.title}</span>
                  <span className="small muted">{statusLabel(b.status)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}

// ── Comments (FR-028) ─────────────────────────────────────────────────────────

export function Comments({ task, readOnly }) {
  const qc = useQueryClient();
  const toast = useToast();
  const { user } = useAuth();
  const [body, setBody] = useState('');
  const [editing, setEditing] = useState(null);
  const q = useQuery({ queryKey: ['comments', task.key], queryFn: () => tasks.comments(task.key) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['comments', task.key] });
  const fail = (e) => toast.error(problem(e).detail);
  const add = useMutation({
    mutationFn: () => tasks.addComment(task.key, body),
    onSuccess: () => (setBody(''), refresh()),
    onError: fail,
  });
  const edit = useMutation({
    mutationFn: () => tasks.editComment(editing.id, editing.body),
    onSuccess: () => (setEditing(null), refresh()),
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: (id) => tasks.deleteComment(id),
    onSuccess: refresh,
    onError: fail,
  });

  return (
    <section className="detail__section" aria-labelledby="comments-h">
      <h3 id="comments-h">Comments</h3>
      {q.isPending && <Skeleton height={64} />}
      {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
      {q.data?.items.length === 0 && <p className="small muted">No comments yet.</p>}
      <ol className="comments">
        {(q.data?.items || []).map((c) => (
          <li key={c.id} className="comment">
            <div className="row row--between small">
              <strong>{c.author.name}</strong>
              <span className="muted">
                {formatDateTime(c.createdAt)}
                {c.edited && ' · edited'}
              </span>
            </div>
            {c.deleted ? (
              <p className="small muted">Comment deleted.</p>
            ) : editing?.id === c.id ? (
              <form
                className="stack"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (editing.body.trim()) edit.mutate();
                }}
              >
                <Field label="Edit comment">
                  {(p) => (
                    <Textarea
                      {...p}
                      value={editing.body}
                      maxLength={5000}
                      rows={3}
                      onChange={(e) => setEditing({ ...editing, body: e.target.value })}
                    />
                  )}
                </Field>
                <div className="row">
                  <Button size="sm" type="submit" variant="primary" loading={edit.isPending}>
                    Save
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setEditing(null)} offlineOk>
                    Cancel
                  </Button>
                </div>
              </form>
            ) : (
              <>
                <Markdown>{c.body}</Markdown>
                {c.canEdit && !readOnly && (
                  <div className="row">
                    {c.author.id === user.id && (
                      <Button
                        size="sm"
                        variant="ghost"
                        icon={<Pencil />}
                        onClick={() => setEditing({ id: c.id, body: c.body })}
                      >
                        Edit
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      icon={<Trash2 />}
                      loading={remove.isPending && remove.variables === c.id}
                      onClick={() => remove.mutate(c.id)}
                    >
                      Delete
                    </Button>
                  </div>
                )}
              </>
            )}
          </li>
        ))}
      </ol>
      {!readOnly && (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (body.trim()) add.mutate();
          }}
        >
          <Field label="Add a comment" help="Markdown supported">
            {(p) => (
              <Textarea
                {...p}
                value={body}
                maxLength={5000}
                rows={3}
                onChange={(e) => setBody(e.target.value)}
              />
            )}
          </Field>
          <div>
            <Button type="submit" loading={add.isPending} disabled={!body.trim()}>
              Comment
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

// ── Activity (task_events) ────────────────────────────────────────────────────

const EVENT_TEXT = {
  CREATED: () => 'created the task',
  STATUS_CHANGED: (e) => `moved it from ${statusLabel(e.fromValue)} to ${statusLabel(e.toValue)}`,
  ASSIGNEE_CHANGED: (e) => (e.toValue ? 'changed the assignee' : 'unassigned it'),
  ESTIMATE_CHANGED: (e) =>
    `changed the estimate to ${e.toValue && e.toValue !== 'null' ? `${e.toValue} h` : 'none'}`,
  DUE_DATE_CHANGED: (e) => `changed the due date to ${e.toValue || 'none'}`,
  AI_SUGGESTION_APPLIED: (e) => `applied an AI suggestion (${e.toValue})`,
  DEPENDENCY_REMOVED: (e) => `removed blocker ${e.fromValue}`,
};

export function Activity({ task }) {
  const [open, setOpen] = useState(false);
  if (!task.activity.length) return null;
  return (
    <section className="detail__section" aria-labelledby="activity-h">
      <div className="row row--between">
        <h3 id="activity-h">Activity</h3>
        <Button size="sm" variant="ghost" offlineOk aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? 'Hide' : `Show ${task.activity.length}`}
        </Button>
      </div>
      {open && (
        <ol className="activity small">
          {task.activity.map((e, i) => (
            <li key={i}>
              <strong>{e.actorName || 'System'}</strong> {(EVENT_TEXT[e.type] || (() => e.type))(e)}{' '}
              <span className="muted">{formatDateTime(e.occurredAt)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
