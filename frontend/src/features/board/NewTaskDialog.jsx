import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Copy, X } from 'lucide-react';
import { problem } from '../../api/client.js';
import { ai, tasks } from '../../api/endpoints.js';
import { Button, IconButton } from '../../components/ui/Button.jsx';
import { PRIORITIES, StatusPill, priorityLabel } from '../../components/ui/Chips.jsx';
import { Dialog } from '../../components/ui/Dialog.jsx';
import { Field, Input, Select, Textarea, fieldErrors } from '../../components/ui/Field.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDebounced } from '../../hooks/misc.js';
import { todayIso } from '../../utils/format.js';

const EMPTY = {
  title: '',
  description: '',
  priority: 'MEDIUM',
  dueDate: '',
  assigneeId: '',
  estimateHours: '',
  labelIds: [],
};

export function NewTaskDialog({ open, onOpenChange, project, members, labels }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [v, setV] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const [dismissed, setDismissed] = useState(false);
  const [matches, setMatches] = useState([]);
  const idempotencyKey = useMemo(() => crypto.randomUUID(), []);
  const debounced = useDebounced(JSON.stringify([v.title, v.description]), 600);
  const [draftTitle, draftDescription] = JSON.parse(debounced);

  // Duplicate check (FR-063): debounced, non-blocking, and silent when the AI service is unavailable.
  useEffect(() => {
    if (draftTitle.trim().length < 3) return undefined;
    const controller = new AbortController();
    ai.duplicates(
      project.key,
      { title: draftTitle.trim(), description: draftDescription || null },
      controller.signal,
    )
      .then((r) => setMatches(r.matches || []))
      .catch(() => setMatches([]));
    return () => controller.abort();
  }, [draftTitle, draftDescription, project.key]);

  const visibleMatches = draftTitle.trim().length >= 3 && !dismissed ? matches : [];

  const m = useMutation({
    mutationFn: (body) => tasks.create(project.key, body, idempotencyKey),
    onSuccess: (t) => {
      qc.invalidateQueries({ queryKey: ['tasks', project.key] });
      qc.invalidateQueries({ queryKey: ['projects'] });
      toast.success(`${t.key} created`);
      onOpenChange(false);
    },
    onError: (e) => {
      const p = problem(e);
      setErrors({ ...fieldErrors(p), _form: p.errors.length ? null : p.detail });
    },
  });

  const submit = (e) => {
    e.preventDefault();
    const errs = {};
    const title = v.title.trim();
    if (title.length < 3 || title.length > 200) errs.title = 'Title needs 3–200 characters';
    if (v.dueDate && v.dueDate < todayIso()) errs.dueDate = 'Pick today or a later date for a new task';
    const est = Number(v.estimateHours);
    if (v.estimateHours !== '' && (est < 0.5 || est > 400 || (est * 2) % 1 !== 0))
      errs.estimateHours = 'Use 0.5 to 400 hours in steps of 0.5';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    m.mutate({
      title,
      description: v.description || null,
      priority: v.priority,
      dueDate: v.dueDate || null,
      assigneeId: v.assigneeId ? Number(v.assigneeId) : null,
      estimateHours: v.estimateHours === '' ? null : est,
      labelIds: v.labelIds,
    });
  };
  const set = (k) => (e) => setV({ ...v, [k]: e.target.value });
  const dirty = v.title || v.description;

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title="New task"
      busy={m.isPending}
      className="dialog--wide"
    >
      <form className="grid-form" onSubmit={submit} noValidate>
        {errors._form && <p className="span-2 field__error">{errors._form}</p>}
        <Field label="Title" className="span-2" error={errors.title}>
          {(p) => <Input {...p} value={v.title} onChange={set('title')} maxLength={200} autoFocus />}
        </Field>

        {visibleMatches.length > 0 && (
          <div className="span-2 dup" role="status">
            <div className="row row--between">
              <strong className="row">
                <Copy aria-hidden="true" /> This looks like existing work
              </strong>
              <IconButton
                label="Dismiss duplicate warning"
                size="sm"
                variant="ghost"
                onClick={() => setDismissed(true)}
              >
                <X />
              </IconButton>
            </div>
            <ul className="dup__list">
              {visibleMatches.map((d) => (
                <li key={d.taskKey} className="dup__item">
                  <Link
                    to={`/projects/${project.key}/tasks/${d.taskKey}`}
                    onClick={(e) => {
                      if (dirty && !window.confirm('Discard this draft and open the existing task?'))
                        e.preventDefault();
                      else onOpenChange(false);
                    }}
                  >
                    <span className="mono">{d.taskKey}</span> {d.title}
                  </Link>
                  <span className="row small">
                    <StatusPill status={d.status} />
                    <span className="muted">{Math.round(d.similarity * 100)}% similar</span>
                  </span>
                </li>
              ))}
            </ul>
            <p className="small muted">You can still create it if it's different.</p>
          </div>
        )}

        <Field label="Description" className="span-2" help="Markdown supported" error={errors.description}>
          {(p) => (
            <Textarea {...p} value={v.description} onChange={set('description')} maxLength={10000} rows={4} />
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
          {(p) => <Input {...p} type="date" min={todayIso()} value={v.dueDate} onChange={set('dueDate')} />}
        </Field>
        <Field label="Assignee" error={errors.assigneeId}>
          {(p) => (
            <Select {...p} value={v.assigneeId} onChange={set('assigneeId')}>
              <option value="">Unassigned</option>
              {members.map((mb) => (
                <option key={mb.userId} value={mb.userId}>
                  {mb.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Estimate (hours)" error={errors.estimateHours} help="Optional">
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
        {labels.length > 0 && (
          <fieldset className="span-2 labels-pick">
            <legend className="field__label">Labels</legend>
            {labels.map((l) => (
              <label key={l.id} className="checkbox chip">
                <input
                  type="checkbox"
                  checked={v.labelIds.includes(l.id)}
                  onChange={(e) =>
                    setV({
                      ...v,
                      labelIds: e.target.checked
                        ? [...v.labelIds, l.id].slice(0, 10)
                        : v.labelIds.filter((x) => x !== l.id),
                    })
                  }
                />
                <span className={`chip__dot dot--${l.color}`} aria-hidden="true" />
                {l.name}
              </label>
            ))}
          </fieldset>
        )}
        <div className="dialog__actions span-2">
          <Button onClick={() => onOpenChange(false)} disabled={m.isPending} offlineOk>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={m.isPending}>
            {visibleMatches.length > 0 ? 'Create anyway' : 'Create task'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
