import { useState } from 'react';
import * as RD from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Copy, KeyRound, Plus, ShieldOff } from 'lucide-react';
import { API_BASE_URL, problem } from '../../api/client.js';
import { apiKeys } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button, IconButton } from '../../components/ui/Button.jsx';
import { ConfirmDialog, Dialog } from '../../components/ui/Dialog.jsx';
import { EmptyState, ErrorNotice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input, Select } from '../../components/ui/Field.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { formatDateTime } from '../../utils/format.js';

const STATUS_CLASS = { ACTIVE: 'chip--low', REVOKED: 'chip--high', EXPIRED: 'chip--medium' };

export default function ApiKeysPage() {
  useDocumentTitle('API keys');
  const { user } = useAuth();
  const toast = useToast();
  const qc = useQueryClient();
  const isAdmin = user.role === 'ADMIN';
  const [all, setAll] = useState(false);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState(null);
  const [revoking, setRevoking] = useState(null);
  const q = useQuery({ queryKey: ['api-keys', all], queryFn: () => apiKeys.list(all) });
  const revoke = useMutation({
    mutationFn: (k) => apiKeys.revoke(k.id),
    onSuccess: (_r, k) => {
      toast.success(`Key ${k.name} revoked`);
      setRevoking(null);
      qc.invalidateQueries({ queryKey: ['api-keys'] });
    },
    onError: (e) => toast.error(problem(e).detail),
  });
  const keys = q.data || [];
  const activeCount = keys.filter((k) => k.status === 'ACTIVE' && (!all || k.ownerId === user.id)).length;

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>API keys</h1>
          <p>Read project data from scripts and other tools, without your password.</p>
        </div>
        <Button
          variant="primary"
          icon={<Plus />}
          onClick={() => setCreating(true)}
          disabled={!all && activeCount >= 5}
          title={!all && activeCount >= 5 ? 'You already have 5 active keys' : undefined}
        >
          Create key
        </Button>
      </div>

      <section className="glass panel stack" aria-labelledby="usage-h">
        <h2 id="usage-h">How to use a key</h2>
        <p className="small">
          Send it in the <code>X-API-Key</code> header. Keys are read-only, limited to 100 requests per
          minute, and see only the projects you can see.
        </p>
        <pre className="code clay-inset">
          <code>{`curl -H "X-API-Key: asp_xxxxxxxx_…" ${API_BASE_URL}/api/public/v1/projects`}</code>
        </pre>
      </section>

      <section className="glass panel" aria-labelledby="keys-h">
        <div className="row row--between panel__title">
          <h2 id="keys-h">{all ? 'All keys' : 'Your keys'}</h2>
          {isAdmin && (
            <label className="checkbox small">
              <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> Show every
              user&apos;s keys
            </label>
          )}
        </div>
        {q.isPending && <Skeleton height={120} />}
        {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
        {q.isSuccess && keys.length === 0 && (
          <EmptyState
            icon={<KeyRound aria-hidden="true" />}
            title="No API keys"
            action={
              <Button icon={<Plus />} onClick={() => setCreating(true)}>
                Create key
              </Button>
            }
          >
            Create a key to read project data from scripts or other tools.
          </EmptyState>
        )}
        {keys.length > 0 && (
          <table className="table table--stack">
            <thead>
              <tr>
                <th scope="col">Name</th>
                {all && <th scope="col">Owner</th>}
                <th scope="col">Prefix</th>
                <th scope="col">Status</th>
                <th scope="col">Last used</th>
                <th scope="col">Requests</th>
                <th scope="col">Expires</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id}>
                  <td data-label="Name">{k.name}</td>
                  {all && <td data-label="Owner">{k.ownerName}</td>}
                  <td data-label="Prefix" className="mono">
                    asp_{k.prefix}…
                  </td>
                  <td data-label="Status">
                    <span className={`chip ${STATUS_CLASS[k.status]}`}>{k.status.toLowerCase()}</span>
                  </td>
                  <td data-label="Last used" className="small">
                    {k.lastUsedAt ? formatDateTime(k.lastUsedAt) : 'Never'}
                  </td>
                  <td data-label="Requests">{k.requestCount}</td>
                  <td data-label="Expires" className="small">
                    {k.expiresAt ? formatDateTime(k.expiresAt) : 'Never'}
                  </td>
                  <td data-label="Actions">
                    {k.status === 'ACTIVE' && (
                      <Button size="sm" variant="ghost" icon={<ShieldOff />} onClick={() => setRevoking(k)}>
                        Revoke
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {creating && (
        <CreateKeyDialog
          onClose={() => setCreating(false)}
          onCreated={(k) => (setCreating(false), setCreated(k))}
        />
      )}
      {created && <CreatedKeyDialog created={created} onClose={() => setCreated(null)} />}
      <ConfirmDialog
        open={Boolean(revoking)}
        onOpenChange={(o) => !o && setRevoking(null)}
        title={revoking ? `Revoke key ${revoking.name}?` : 'Revoke key'}
        description={
          revoking
            ? `asp_${revoking.prefix}… stops working immediately. Requests using it will fail. This can't be undone.`
            : ''
        }
        confirmLabel="Revoke key"
        busy={revoke.isPending}
        onConfirm={() => revoke.mutate(revoking)}
      />
    </div>
  );
}

function CreateKeyDialog({ onClose, onCreated }) {
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [days, setDays] = useState('90');
  const [error, setError] = useState(null);
  const m = useMutation({
    mutationFn: () => apiKeys.create({ name: name.trim(), expiresInDays: days ? Number(days) : null }),
    onSuccess: (k) => {
      qc.invalidateQueries({ queryKey: ['api-keys'] });
      onCreated(k);
    },
    onError: (e) => setError(problem(e).detail),
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Create API key"
      description="Name it after where it's used, like ci-script."
      busy={m.isPending}
    >
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          if (!name.trim()) return setError('Give the key a name');
          m.mutate();
        }}
        noValidate
      >
        <Field label="Name" error={error}>
          {(p) => (
            <Input {...p} value={name} maxLength={50} autoFocus onChange={(e) => setName(e.target.value)} />
          )}
        </Field>
        <Field label="Expires">
          {(p) => (
            <Select {...p} value={days} onChange={(e) => setDays(e.target.value)}>
              <option value="30">In 30 days</option>
              <option value="90">In 90 days</option>
              <option value="180">In 180 days</option>
              <option value="365">In 1 year</option>
              <option value="">Never</option>
            </Select>
          )}
        </Field>
        <div className="dialog__actions">
          <Button onClick={onClose} disabled={m.isPending} offlineOk>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={m.isPending}>
            Create key
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** The full key is shown exactly once (FR-040); closing requires confirming it was stored. */
function CreatedKeyDialog({ created, onClose }) {
  const [stored, setStored] = useState(false);
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(created.key);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      document.getElementById('created-key')?.select();
    }
  };
  return (
    <RD.Root open onOpenChange={(o) => !o && stored && onClose()}>
      <RD.Portal>
        <RD.Overlay className="scrim" />
        <RD.Content
          className="dialog glass glass--strong"
          onEscapeKeyDown={(e) => !stored && e.preventDefault()}
          onPointerDownOutside={(e) => e.preventDefault()}
        >
          <RD.Title className="dialog__title">Copy your new key</RD.Title>
          <RD.Description className="dialog__desc">{created.notice}</RD.Description>
          <div className="row key-reveal">
            <label className="visually-hidden" htmlFor="created-key">
              API key {created.name}
            </label>
            <Input
              id="created-key"
              className="input mono grow"
              readOnly
              value={created.key}
              onFocus={(e) => e.target.select()}
            />
            <IconButton label={copied ? 'Copied' : 'Copy key'} onClick={copy}>
              {copied ? <Check /> : <Copy />}
            </IconButton>
          </div>
          <p className="small muted" aria-live="polite">
            {copied ? 'Copied to clipboard.' : ''}
          </p>
          <label className="checkbox">
            <input type="checkbox" checked={stored} onChange={(e) => setStored(e.target.checked)} /> I have
            stored this key safely
          </label>
          <div className="dialog__actions">
            <Button
              variant="primary"
              disabled={!stored}
              offlineOk
              onClick={onClose}
              title={stored ? undefined : 'Confirm you stored the key first'}
            >
              Done
            </Button>
          </div>
        </RD.Content>
      </RD.Portal>
    </RD.Root>
  );
}
