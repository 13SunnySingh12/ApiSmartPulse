import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, CheckCircle2, Sparkles } from 'lucide-react';
import { problem } from '../../api/client.js';
import { analytics, tasks } from '../../api/endpoints.js';
import { Button } from '../../components/ui/Button.jsx';
import { Avatar } from '../../components/ui/Chips.jsx';
import { ConfirmDialog } from '../../components/ui/Dialog.jsx';
import { EmptyState, ErrorNotice, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Bar } from '../../components/ui/Segmented.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { hours } from '../../utils/format.js';
import { useProject } from '../projects/ProjectLayout.jsx';

const STATUS_TEXT = { UNDER: 'Under', HEALTHY: 'Healthy', OVER: 'Over' };

export default function WorkloadPage() {
  const project = useProject();
  useDocumentTitle('Workload', project.key);
  const qc = useQueryClient();
  const toast = useToast();
  const [dismissed, setDismissed] = useState([]);
  const [confirm, setConfirm] = useState(null);
  const q = useQuery({ queryKey: ['workload', project.key], queryFn: () => analytics.workload(project.key) });
  const canReassign = project.canManage && project.status === 'ACTIVE';

  const reassign = useMutation({
    mutationFn: async (s) => {
      const t = await tasks.get(s.taskKey); // fresh version for optimistic locking
      return tasks.update(s.taskKey, { assigneeId: s.toUserId, version: t.version });
    },
    onSuccess: (_r, s) => {
      toast.success(`${s.taskKey} reassigned to ${s.toName}`);
      setConfirm(null);
      qc.invalidateQueries({ queryKey: ['workload', project.key] });
      qc.invalidateQueries({ queryKey: ['tasks', project.key] });
    },
    onError: (e) => toast.error(problem(e).detail),
  });

  if (q.isPending) {
    return (
      <section className="glass panel stack" aria-busy="true">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} height={44} />
        ))}
      </section>
    );
  }
  if (q.isError) return <ErrorNotice error={q.error} onRetry={q.refetch} title="Couldn't load workload" />;
  const w = q.data;
  const suggestions = w.suggestions.filter((s) => !dismissed.includes(s.taskKey));
  const assumed = w.members.reduce((sum, m) => sum + m.assumedHours, 0);
  const anyOver = w.members.some((m) => m.status === 'OVER');

  if (w.members.length === 0) {
    return (
      <section className="glass panel">
        <EmptyState
          title="No members yet"
          action={
            <Link to="../settings" className="btn btn--primary">
              Go to settings
            </Link>
          }
        >
          Add members and their weekly capacity to see workload.
        </EmptyState>
      </section>
    );
  }

  return (
    <div className="page">
      <section className="glass panel" aria-labelledby="wl-h">
        <div className="row row--between panel__title">
          <h2 id="wl-h">Workload this week</h2>
          <span className="small muted">Open hours vs weekly capacity</span>
        </div>
        <table className="table table--stack workload">
          <thead>
            <tr>
              <th scope="col">Member</th>
              <th scope="col">Capacity</th>
              <th scope="col">Open hours</th>
              <th scope="col">Load</th>
            </tr>
          </thead>
          <tbody>
            {w.members.map((m) => (
              <tr key={m.userId}>
                <td data-label="Member">
                  <span className="row">
                    <Avatar name={m.name} /> {m.name}
                  </span>
                </td>
                <td data-label="Capacity">{hours(m.capacityHours)}</td>
                <td data-label="Open hours">
                  {hours(m.openHours)}
                  {m.assumedHours > 0 && (
                    <span className="small muted"> (incl. {hours(m.assumedHours)} assumed)</span>
                  )}
                </td>
                <td data-label="Load" className="workload__load">
                  <Bar value={m.loadPct} status={m.status} label={`${m.name} load ${m.loadPct}%`} />
                  <span
                    className={`chip ${m.status === 'OVER' ? 'chip--high' : m.status === 'UNDER' ? 'chip--low' : 'chip--progress'}`}
                  >
                    {m.loadPct}% · {STATUS_TEXT[m.status]}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="small muted workload__note">
          {w.unassignedHours > 0 && <>Unassigned work: {hours(w.unassignedHours)}. </>}
          {assumed > 0 && <>Tasks without an estimate or prediction count as 4 h (assumed).</>}
        </p>
      </section>

      {!anyOver && (
        <Notice kind="success" title="Balanced">
          Nobody is over capacity this week.
        </Notice>
      )}

      {suggestions.map((s) => (
        <div key={s.taskKey} className="ai-card clay">
          <Sparkles className="ai-card__icon" aria-hidden="true" />
          <div className="ai-card__body">
            <p>
              Suggestion: move{' '}
              <Link to={`/projects/${project.key}/tasks/${s.taskKey}`} className="mono">
                {s.taskKey}
              </Link>{' '}
              ({hours(s.hours)}, {s.taskTitle}) from <strong>{s.fromName}</strong>{' '}
              <ArrowRight aria-label="to" className="icon-inline" /> <strong>{s.toName}</strong>.
            </p>
            <p className="small muted">
              {s.fromName} {s.fromLoadBefore}% → {s.fromLoadAfter}%, {s.toName} {s.toLoadBefore}% →{' '}
              {s.toLoadAfter}%. Rule-based.
            </p>
            <div className="row">
              {canReassign ? (
                <Button size="sm" variant="primary" onClick={() => setConfirm(s)}>
                  Reassign
                </Button>
              ) : (
                <span className="small muted">Only the project owner or an admin can reassign.</span>
              )}
              <Button
                size="sm"
                variant="ghost"
                offlineOk
                onClick={() => setDismissed([...dismissed, s.taskKey])}
              >
                Dismiss
              </Button>
            </div>
          </div>
        </div>
      ))}

      {w.noSafeMove.map((u) => (
        <Notice key={u.userId} kind="warning" title={`${u.name} is over capacity`}>
          No safe move: nobody can take their lowest-priority unblocked task without going over 100%. Consider
          extending the due date.
        </Notice>
      ))}
      {anyOver && suggestions.length === 0 && w.noSafeMove.length === 0 && (
        <p className="small muted">
          <CheckCircle2 aria-hidden="true" className="icon-inline" /> All suggestions dismissed.
        </p>
      )}

      <ConfirmDialog
        open={Boolean(confirm)}
        onOpenChange={(o) => !o && setConfirm(null)}
        danger={false}
        title={confirm ? `Reassign ${confirm.taskKey}?` : 'Reassign'}
        description={
          confirm ? `${confirm.taskTitle} moves from ${confirm.fromName} to ${confirm.toName}.` : ''
        }
        confirmLabel="Reassign task"
        busy={reassign.isPending}
        onConfirm={() => reassign.mutate(confirm)}
      />
    </div>
  );
}
