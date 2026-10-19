import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Archive, ArchiveRestore, Check, Pencil, Plus, Trash2, UserMinus, X } from 'lucide-react';
import { problem } from '../../api/client.js';
import { projects } from '../../api/endpoints.js';
import { Button, IconButton } from '../../components/ui/Button.jsx';
import { Avatar, LabelChip } from '../../components/ui/Chips.jsx';
import { ConfirmDialog } from '../../components/ui/Dialog.jsx';
import { ErrorNotice, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Select, Textarea, fieldErrors } from '../../components/ui/Field.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { useProject } from '../projects/ProjectLayout.jsx';

const COLORS = ['slate', 'violet', 'blue', 'teal', 'green', 'amber', 'orange', 'rose'];

export default function SettingsPage() {
  const project = useProject();
  useDocumentTitle('Settings', project.key);
  const editable = project.canManage && project.status === 'ACTIVE';
  return (
    <div className="page settings">
      {!project.canManage && (
        <Notice kind="info">Only the project owner or an admin can change these settings.</Notice>
      )}
      {project.status === 'ARCHIVED' && project.canManage && (
        <Notice kind="info">This project is archived. Unarchive it to make changes.</Notice>
      )}
      <General project={project} editable={editable} />
      <Members project={project} editable={editable} />
      <Labels project={project} editable={project.status === 'ACTIVE'} />
      {project.canManage && <DangerZone project={project} />}
    </div>
  );
}

function useProjectRefresh(key) {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ['project', key] });
    qc.invalidateQueries({ queryKey: ['projects'] });
  };
}

function General({ project, editable }) {
  const toast = useToast();
  const refresh = useProjectRefresh(project.key);
  const [v, setV] = useState({
    name: project.name,
    description: project.description || '',
    startDate: project.startDate || '',
    targetDate: project.targetDate || '',
  });
  const [errors, setErrors] = useState({});
  const save = useMutation({
    mutationFn: () =>
      projects.update(project.key, {
        name: v.name.trim(),
        description: v.description,
        startDate: v.startDate || null,
        targetDate: v.targetDate || null,
        version: project.version,
      }),
    onSuccess: () => {
      toast.success('Project saved');
      setErrors({});
      refresh();
    },
    onError: (e) => {
      const p = problem(e);
      setErrors(
        p.code === 'CONFLICT_STALE'
          ? { _form: 'Someone else changed this project. Reload the page to see the latest version.' }
          : { ...fieldErrors(p), _form: p.errors.length ? null : p.detail },
      );
    },
  });
  const set = (k) => (e) => setV({ ...v, [k]: e.target.value });
  return (
    <section className="glass panel" aria-labelledby="gen-h">
      <h2 id="gen-h" className="panel__title">
        General
      </h2>
      <form
        className="grid-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (v.name.trim().length < 3) return setErrors({ name: 'Name needs at least 3 characters' });
          save.mutate();
        }}
        noValidate
      >
        {errors._form && <p className="span-2 field__error">{errors._form}</p>}
        <Field label="Name" error={errors.name}>
          {(p) => <Input {...p} value={v.name} maxLength={80} disabled={!editable} onChange={set('name')} />}
        </Field>
        <Field label="Key" help="Keys can't change after creation">
          {(p) => <Input {...p} value={project.key} disabled readOnly />}
        </Field>
        <Field label="Description" className="span-2" error={errors.description}>
          {(p) => (
            <Textarea
              {...p}
              value={v.description}
              maxLength={2000}
              rows={3}
              disabled={!editable}
              onChange={set('description')}
            />
          )}
        </Field>
        <Field label="Start date" error={errors.startDate}>
          {(p) => (
            <Input {...p} type="date" value={v.startDate} disabled={!editable} onChange={set('startDate')} />
          )}
        </Field>
        <Field label="Target date" error={errors.targetDate}>
          {(p) => (
            <Input
              {...p}
              type="date"
              value={v.targetDate}
              disabled={!editable}
              onChange={set('targetDate')}
            />
          )}
        </Field>
        {editable && (
          <div className="span-2">
            <Button type="submit" variant="primary" loading={save.isPending}>
              Save changes
            </Button>
          </div>
        )}
      </form>
    </section>
  );
}

function Members({ project, editable }) {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['members', project.key], queryFn: () => projects.members(project.key) });
  const [email, setEmail] = useState('');
  const [capacity, setCapacity] = useState('40');
  const [addError, setAddError] = useState(null);
  const [removing, setRemoving] = useState(null);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['members', project.key] });
    qc.invalidateQueries({ queryKey: ['workload', project.key] });
  };
  const add = useMutation({
    mutationFn: () =>
      projects.addMember(project.key, { userEmail: email.trim(), capacityHours: Number(capacity) }),
    onSuccess: (m) => {
      toast.success(`${m.name} added`);
      setEmail('');
      setAddError(null);
      refresh();
    },
    onError: (e) => setAddError(problem(e).detail),
  });
  const update = useMutation({
    mutationFn: ({ userId, capacityHours }) => projects.updateMember(project.key, userId, { capacityHours }),
    onSuccess: (m) => {
      toast.success(`${m.name}'s capacity is ${m.capacityHours} h/week`);
      refresh();
    },
    onError: (e) => toast.error(problem(e).detail),
  });
  const remove = useMutation({
    mutationFn: (m) => projects.removeMember(project.key, m.userId),
    onSuccess: (_r, m) => {
      toast.success(`${m.name} removed; their open tasks are now unassigned`);
      setRemoving(null);
      refresh();
      qc.invalidateQueries({ queryKey: ['tasks', project.key] });
    },
    onError: (e) => toast.error(problem(e).detail),
  });

  return (
    <section className="glass panel" aria-labelledby="mem-h">
      <h2 id="mem-h" className="panel__title">
        Members
      </h2>
      {q.isPending && <Skeleton height={120} />}
      {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
      {q.data && (
        <table className="table table--stack">
          <thead>
            <tr>
              <th scope="col">Member</th>
              <th scope="col">Email</th>
              <th scope="col">Capacity (h/week)</th>
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {q.data.map((m) => (
              <tr key={m.userId}>
                <td data-label="Member">
                  <span className="row">
                    <Avatar name={m.name} /> {m.name}
                    {m.owner && <span className="chip">Owner</span>}
                    {!m.active && <span className="chip">Deactivated</span>}
                  </span>
                </td>
                <td data-label="Email" className="small">
                  {m.email}
                </td>
                <td data-label="Capacity">
                  <CapacityInput
                    member={m}
                    disabled={!editable}
                    onSave={(capacityHours) => update.mutate({ userId: m.userId, capacityHours })}
                  />
                </td>
                <td data-label="Actions">
                  {editable && !m.owner && (
                    <IconButton
                      label={`Remove ${m.name}`}
                      size="sm"
                      variant="ghost"
                      onClick={() => setRemoving(m)}
                    >
                      <UserMinus />
                    </IconButton>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editable && (
        <form
          className="row add-member"
          onSubmit={(e) => {
            e.preventDefault();
            if (!email.trim()) return setAddError('Enter the email of a registered user');
            add.mutate();
          }}
          noValidate
        >
          <Field label="Add member by email" error={addError} className="grow">
            {(p) => <Input {...p} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
          <Field label="Capacity">
            {(p) => (
              <Input
                {...p}
                type="number"
                min="1"
                max="80"
                step="0.5"
                value={capacity}
                onChange={(e) => setCapacity(e.target.value)}
                className="input input--narrow"
              />
            )}
          </Field>
          <Button type="submit" icon={<Plus />} loading={add.isPending} className="align-end">
            Add
          </Button>
        </form>
      )}
      <ConfirmDialog
        open={Boolean(removing)}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={removing ? `Remove ${removing.name}?` : 'Remove member'}
        description="Their open tasks in this project become unassigned. They keep authorship on history."
        confirmLabel="Remove member"
        busy={remove.isPending}
        onConfirm={() => remove.mutate(removing)}
      />
    </section>
  );
}

function CapacityInput({ member, disabled, onSave }) {
  const [value, setValue] = useState(String(member.capacityHours));
  const n = Number(value);
  const valid = n >= 1 && n <= 80;
  const commit = () => valid && n !== Number(member.capacityHours) && onSave(n);
  return (
    <span className="row">
      <label className="visually-hidden" htmlFor={`cap-${member.userId}`}>
        Capacity for {member.name}
      </label>
      <Input
        id={`cap-${member.userId}`}
        className="input input--narrow"
        type="number"
        min="1"
        max="80"
        step="0.5"
        value={value}
        disabled={disabled}
        aria-invalid={!valid || undefined}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Enter' && commit()}
      />
      {!valid && <span className="field__error">1–80</span>}
    </span>
  );
}

function Labels({ project, editable }) {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['labels', project.key], queryFn: () => projects.labels(project.key) });
  const [draft, setDraft] = useState({ name: '', color: 'violet' });
  const [editing, setEditing] = useState(null);
  const [error, setError] = useState(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['labels', project.key] });
  const fail = (e) => setError(problem(e).detail);
  const create = useMutation({
    mutationFn: () => projects.createLabel(project.key, { name: draft.name.trim(), color: draft.color }),
    onSuccess: () => (setDraft({ ...draft, name: '' }), setError(null), refresh()),
    onError: fail,
  });
  const update = useMutation({
    mutationFn: () => projects.updateLabel(editing.id, { name: editing.name.trim(), color: editing.color }),
    onSuccess: () => (setEditing(null), setError(null), refresh()),
    onError: fail,
  });
  const remove = useMutation({
    mutationFn: (id) => projects.deleteLabel(id),
    onSuccess: () => (refresh(), toast.success('Label deleted')),
    onError: fail,
  });

  return (
    <section className="glass panel" aria-labelledby="lab-h">
      <h2 id="lab-h" className="panel__title">
        Labels
      </h2>
      {q.isPending && <Skeleton height={40} />}
      {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
      {error && <p className="field__error">{error}</p>}
      <ul className="label-list">
        {(q.data || []).map((l) =>
          editing?.id === l.id ? (
            <li key={l.id} className="row">
              <LabelFields value={editing} onChange={setEditing} idPrefix={`edit-${l.id}`} />
              <IconButton
                label="Save label"
                size="sm"
                loading={update.isPending}
                onClick={() => editing.name.trim() && update.mutate()}
              >
                <Check />
              </IconButton>
              <IconButton label="Cancel" size="sm" variant="ghost" onClick={() => setEditing(null)}>
                <X />
              </IconButton>
            </li>
          ) : (
            <li key={l.id} className="row">
              <LabelChip label={l} />
              {editable && (
                <>
                  <IconButton
                    label={`Edit label ${l.name}`}
                    size="sm"
                    variant="ghost"
                    onClick={() => setEditing({ ...l })}
                  >
                    <Pencil />
                  </IconButton>
                  <IconButton
                    label={`Delete label ${l.name}`}
                    size="sm"
                    variant="ghost"
                    onClick={() => remove.mutate(l.id)}
                  >
                    <Trash2 />
                  </IconButton>
                </>
              )}
            </li>
          ),
        )}
      </ul>
      {q.data?.length === 0 && <p className="small muted">No labels yet.</p>}
      {editable && (
        <form
          className="row add-label"
          onSubmit={(e) => {
            e.preventDefault();
            if (draft.name.trim()) create.mutate();
          }}
        >
          <LabelFields value={draft} onChange={setDraft} idPrefix="new-label" />
          <Button
            type="submit"
            icon={<Plus />}
            loading={create.isPending}
            disabled={!draft.name.trim()}
            className="align-end"
          >
            Add label
          </Button>
        </form>
      )}
    </section>
  );
}

function LabelFields({ value, onChange, idPrefix }) {
  return (
    <>
      <Field id={`${idPrefix}-name`} label="Name">
        {(p) => (
          <Input
            {...p}
            value={value.name}
            maxLength={30}
            onChange={(e) => onChange({ ...value, name: e.target.value })}
          />
        )}
      </Field>
      <Field id={`${idPrefix}-color`} label="Colour">
        {(p) => (
          <Select {...p} value={value.color} onChange={(e) => onChange({ ...value, color: e.target.value })}>
            {COLORS.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </>
  );
}

function DangerZone({ project }) {
  const toast = useToast();
  const refresh = useProjectRefresh(project.key);
  const [confirm, setConfirm] = useState(false);
  const archived = project.status === 'ARCHIVED';
  const m = useMutation({
    mutationFn: () => (archived ? projects.unarchive(project.key) : projects.archive(project.key)),
    onSuccess: () => {
      toast.success(archived ? `${project.key} is active again` : `${project.key} archived`);
      setConfirm(false);
      refresh();
    },
    onError: (e) => toast.error(problem(e).detail),
  });
  return (
    <section className="glass panel danger" aria-labelledby="dz-h">
      <h2 id="dz-h" className="panel__title">
        Danger zone
      </h2>
      <div className="row row--between">
        <p className="small">
          {archived
            ? 'Unarchiving makes the project editable again.'
            : 'Archiving makes the project and its tasks read-only. You can unarchive later.'}
        </p>
        <Button
          variant={archived ? 'secondary' : 'danger'}
          icon={archived ? <ArchiveRestore /> : <Archive />}
          onClick={() => (archived ? m.mutate() : setConfirm(true))}
          loading={archived && m.isPending}
        >
          {archived ? 'Unarchive project' : 'Archive project'}
        </Button>
      </div>
      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={`Archive ${project.name}?`}
        description="All tasks become read-only, including AI helpers that write. You can unarchive at any time."
        confirmLabel="Archive project"
        busy={m.isPending}
        onConfirm={() => m.mutate()}
      />
    </section>
  );
}
