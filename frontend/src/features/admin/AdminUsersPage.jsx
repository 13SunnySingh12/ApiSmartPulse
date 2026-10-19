import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { UserCheck, UserX } from 'lucide-react';
import { problem } from '../../api/client.js';
import { admin } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthProvider.jsx';
import { Button } from '../../components/ui/Button.jsx';
import { Avatar } from '../../components/ui/Chips.jsx';
import { ConfirmDialog } from '../../components/ui/Dialog.jsx';
import { ErrorNotice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Select } from '../../components/ui/Field.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { formatDateTime } from '../../utils/format.js';

export default function AdminUsersPage() {
  useDocumentTitle('Users', 'Admin');
  const { user: me } = useAuth();
  const toast = useToast();
  const qc = useQueryClient();
  const [deactivating, setDeactivating] = useState(null);
  const q = useQuery({ queryKey: ['admin-users'], queryFn: admin.users });
  const update = useMutation({
    mutationFn: ({ id, body }) => admin.updateUser(id, body),
    onSuccess: (u) => {
      toast.success(`${u.name} updated`);
      setDeactivating(null);
      qc.invalidateQueries({ queryKey: ['admin-users'] });
    },
    onError: (e) => {
      const p = problem(e);
      toast.error(p.code === 'LAST_ADMIN' ? 'At least one active admin must remain.' : p.detail);
      qc.invalidateQueries({ queryKey: ['admin-users'] });
    },
  });

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>Users</h1>
          <p>Change roles and deactivate accounts. Changes sign the user out of other sessions.</p>
        </div>
      </div>
      <section className="glass panel" aria-label="Users">
        {q.isPending && <Skeleton height={200} />}
        {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
        {q.data && (
          <table className="table table--stack">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">Role</th>
                <th scope="col">Status</th>
                <th scope="col">Last login</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {q.data.map((u) => (
                <tr key={u.id}>
                  <td data-label="Name">
                    <span className="row">
                      <Avatar name={u.name} /> {u.name}
                      {u.id === me.id && <span className="chip">You</span>}
                    </span>
                  </td>
                  <td data-label="Email" className="small">
                    {u.email}
                  </td>
                  <td data-label="Role">
                    <label className="visually-hidden" htmlFor={`role-${u.id}`}>
                      Role for {u.name}
                    </label>
                    <Select
                      id={`role-${u.id}`}
                      value={u.role}
                      disabled={update.isPending}
                      onChange={(e) => update.mutate({ id: u.id, body: { role: e.target.value } })}
                    >
                      <option value="MEMBER">Member</option>
                      <option value="MANAGER">Manager</option>
                      <option value="ADMIN">Admin</option>
                    </Select>
                  </td>
                  <td data-label="Status">
                    <span className={`chip ${u.active ? 'chip--low' : ''}`}>
                      {u.active ? 'Active' : 'Deactivated'}
                    </span>
                  </td>
                  <td data-label="Last login" className="small">
                    {u.lastLoginAt ? formatDateTime(u.lastLoginAt) : 'Never'}
                  </td>
                  <td data-label="Actions">
                    {u.id !== me.id &&
                      (u.active ? (
                        <Button size="sm" variant="ghost" icon={<UserX />} onClick={() => setDeactivating(u)}>
                          Deactivate
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant="ghost"
                          icon={<UserCheck />}
                          onClick={() => update.mutate({ id: u.id, body: { active: true } })}
                        >
                          Reactivate
                        </Button>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <ConfirmDialog
        open={Boolean(deactivating)}
        onOpenChange={(o) => !o && setDeactivating(null)}
        title={deactivating ? `Deactivate ${deactivating.name}?` : 'Deactivate'}
        description="They can't log in and their sessions end. Their tasks and comments stay, marked as deactivated."
        confirmLabel="Deactivate user"
        busy={update.isPending}
        onConfirm={() => update.mutate({ id: deactivating.id, body: { active: false } })}
      />
    </div>
  );
}
