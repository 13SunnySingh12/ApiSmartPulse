import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Brain, DatabaseZap } from 'lucide-react';
import { problem } from '../../api/client.js';
import { admin } from '../../api/endpoints.js';
import { Button } from '../../components/ui/Button.jsx';
import { ErrorNotice, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { useToast } from '../../components/ui/Toast.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { formatDateTime } from '../../utils/format.js';

const MODELS = [
  {
    name: 'ESTIMATOR',
    title: 'Duration estimator',
    metric: (m) => (m?.mae != null ? `MAE ${m.mae.toFixed(2)} h` : 'MAE n/a'),
  },
  {
    name: 'RISK',
    title: 'Overdue risk',
    metric: (m) =>
      m?.f1 != null
        ? `F1 ${m.f1.toFixed(2)}${m.roc_auc != null ? ` · AUC ${m.roc_auc.toFixed(2)}` : ''}`
        : 'F1 n/a',
  },
  {
    name: 'PRIORITY',
    title: 'Suggested priority',
    metric: (m) => (m?.macro_f1 != null ? `Macro-F1 ${m.macro_f1.toFixed(2)}` : 'Macro-F1 n/a'),
  },
];
const STATUS_CLASS = {
  SUCCEEDED: 'chip--low',
  FAILED: 'chip--high',
  RUNNING: 'chip--progress',
  SKIPPED_INSUFFICIENT_DATA: 'chip--medium',
};
const parse = (s) => {
  try {
    return s ? JSON.parse(s) : null;
  } catch {
    return null;
  }
};

export default function AdminMlPage() {
  useDocumentTitle('Models', 'Admin');
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ['ml-models'],
    queryFn: admin.models,
    refetchInterval: (query) => (query.state.data?.runs.some((r) => r.status === 'RUNNING') ? 3000 : false),
  });
  const fail = (e) => {
    const p = problem(e);
    toast.error(p.code === 'TRAINING_IN_PROGRESS' ? 'A training run is already in progress.' : p.detail, {
      requestId: p.requestId,
    });
  };
  const train = useMutation({
    mutationFn: admin.train,
    onSuccess: () => {
      toast.info('Training started. Results appear below in a minute or two.');
      qc.invalidateQueries({ queryKey: ['ml-models'] });
    },
    onError: fail,
  });
  const reindex = useMutation({
    mutationFn: admin.reindex,
    onSuccess: (r) => {
      toast.success(`${r.queued} tasks and comments queued for re-indexing`);
      qc.invalidateQueries({ queryKey: ['ml-models'] });
    },
    onError: fail,
  });
  const running = q.data?.runs.some((r) => r.status === 'RUNNING');

  return (
    <div className="page">
      <div className="page__header">
        <div>
          <h1>Models</h1>
          <p>Train the prediction models and keep search embeddings in sync.</p>
        </div>
        <div className="row">
          <Button icon={<DatabaseZap />} loading={reindex.isPending} onClick={() => reindex.mutate()}>
            Re-index embeddings
          </Button>
          <Button
            variant="primary"
            icon={<Brain />}
            loading={train.isPending || running}
            onClick={() => train.mutate()}
          >
            {running ? 'Training…' : 'Train models'}
          </Button>
        </div>
      </div>
      {q.isPending && <Skeleton height={160} />}
      {q.isError && <ErrorNotice error={q.error} onRetry={q.refetch} />}
      {q.data && (
        <>
          <section className="stats" aria-label="Active models">
            {MODELS.map((m) => {
              const active = q.data.active.find((a) => a.modelName === m.name);
              return (
                <div key={m.name} className="stat clay">
                  <span className="stat__label">{m.title}</span>
                  {active ? (
                    <>
                      <span className="stat__value stat__value--sm">{m.metric(parse(active.metrics))}</span>
                      <span className="small muted">
                        v{active.version} · {formatDateTime(active.finishedAt)} · {active.trainingRows} rows
                      </span>
                    </>
                  ) : (
                    <>
                      <span className="stat__value stat__value--sm">Rules</span>
                      <span className="small muted">Not trained yet: predictions use heuristics.</span>
                    </>
                  )}
                </div>
              );
            })}
          </section>
          <Notice kind="info">
            Metrics are measured on a time-ordered 20% hold-out of this installation&apos;s history. On the
            demo seed data they describe synthetic tasks, not real-world accuracy.
          </Notice>
          <section className="glass panel" aria-labelledby="sync-h">
            <h2 id="sync-h" className="panel__title">
              Embedding sync
            </h2>
            <p className="small">
              Pending {q.data.outbox.PENDING} · Synced {q.data.outbox.DONE} · Failed {q.data.outbox.FAILED}
            </p>
            {q.data.outbox.FAILED > 0 && (
              <Notice kind="warning">
                Some items failed to sync after 10 attempts. Re-index to queue them again once the AI service
                is healthy.
              </Notice>
            )}
          </section>
          <section className="glass panel" aria-labelledby="runs-h">
            <h2 id="runs-h" className="panel__title">
              Recent runs
            </h2>
            {q.data.runs.length === 0 ? (
              <p className="small muted">No training runs yet.</p>
            ) : (
              <table className="table table--stack">
                <thead>
                  <tr>
                    <th scope="col">Model</th>
                    <th scope="col">Version</th>
                    <th scope="col">Status</th>
                    <th scope="col">Rows</th>
                    <th scope="col">Metric</th>
                    <th scope="col">Started</th>
                  </tr>
                </thead>
                <tbody>
                  {q.data.runs.map((r) => (
                    <tr key={r.id}>
                      <td data-label="Model">{MODELS.find((m) => m.name === r.modelName)?.title}</td>
                      <td data-label="Version" className="mono">
                        {r.version}
                        {r.active && <span className="chip chip--low">active</span>}
                      </td>
                      <td data-label="Status">
                        <span
                          className={`chip ${STATUS_CLASS[r.status]}`}
                          title={r.errorMessage || undefined}
                        >
                          {r.status.replaceAll('_', ' ').toLowerCase()}
                        </span>
                      </td>
                      <td data-label="Rows">{r.trainingRows ?? '–'}</td>
                      <td data-label="Metric" className="small">
                        {r.status === 'SUCCEEDED'
                          ? MODELS.find((m) => m.name === r.modelName)?.metric(parse(r.metrics))
                          : r.errorMessage || '–'}
                      </td>
                      <td data-label="Started" className="small">
                        {formatDateTime(r.startedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </div>
  );
}
