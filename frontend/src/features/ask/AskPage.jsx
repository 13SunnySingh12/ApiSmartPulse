import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { MessageSquareText, SendHorizontal, Sparkles } from 'lucide-react';
import { problem } from '../../api/client.js';
import { ai } from '../../api/endpoints.js';
import { Button } from '../../components/ui/Button.jsx';
import { FilterChip, StatusPill } from '../../components/ui/Chips.jsx';
import { EmptyState, Notice, Skeleton } from '../../components/ui/Feedback.jsx';
import { Field, Input } from '../../components/ui/Field.jsx';
import { Markdown } from '../../components/ui/Markdown.jsx';
import { useDocumentTitle } from '../../hooks/misc.js';
import { addDaysIso, todayIso } from '../../utils/format.js';
import { useProject } from '../projects/ProjectLayout.jsx';

const EXAMPLES = [
  'What slipped last month and why?',
  'Which tasks are blocked by reviews?',
  'What did we finish for the API?',
];
const DEGRADED = {
  LLM_DISABLED: 'AI answers are turned off for this installation. Showing matching tasks instead.',
  LLM_QUOTA_EXHAUSTED:
    'AI answer unavailable right now (usage limit reached). Showing matching tasks instead.',
  LLM_TIMEOUT: 'The AI took too long to answer. Showing matching tasks instead.',
  LLM_ERROR: 'The AI provider had a problem. Showing matching tasks instead.',
};

export default function AskPage() {
  const project = useProject();
  useDocumentTitle('Ask', project.key);
  const [question, setQuestion] = useState('');
  const [range, setRange] = useState('30');
  const [custom, setCustom] = useState({ from: addDaysIso(todayIso(), -30), to: todayIso() });
  const [error, setError] = useState(null);
  const [history, setHistory] = useState([]); // this session only (FR-065)

  const ask = useMutation({
    mutationFn: (body) => ai.ask(project.key, body),
    onSuccess: (r, body) =>
      setHistory((h) => [{ id: crypto.randomUUID(), q: body.question, r }, ...h].slice(0, 10)),
    onError: (e) => {
      const p = problem(e);
      setError(
        p.code === 'AI_RATE_LIMITED'
          ? `You've asked a lot in a short time. Try again in ${p.retryAfter || 60} s.`
          : p.code === 'AI_WARMING_UP'
            ? 'The AI model is still loading. Try again in a minute.'
            : p.code === 'AI_UNAVAILABLE'
              ? 'The AI service is unavailable right now. Your tasks are unaffected.'
              : p.errors[0]?.message
                ? `Question ${p.errors[0].message}`
                : p.detail,
      );
    },
  });

  const submit = (e, text = question) => {
    e?.preventDefault();
    const q = text.trim();
    if (q.length < 3 || q.length > 500) {
      setError('Ask a question of 3 to 500 characters.');
      return;
    }
    let from = null;
    let to = null;
    if (range === 'custom') {
      ({ from, to } = custom);
      if (from && to && to < from) return setError('The end date must be on or after the start date.');
    } else if (range !== 'all') {
      from = addDaysIso(todayIso(), -Number(range));
      to = todayIso();
    }
    setError(null);
    setQuestion(q);
    ask.mutate({ question: q, from: from || null, to: to || null });
  };

  const latest = history[0];

  return (
    <div className="ask">
      <section className="glass panel stack" aria-labelledby="ask-h">
        <h2 id="ask-h">Ask {project.key}</h2>
        <form className="ask__form" onSubmit={submit} noValidate>
          <Field
            label="Your question"
            error={error}
            help="Answers come only from this project's tasks and comments. Don't include personal or secret information."
          >
            {(p) => (
              <Input
                {...p}
                value={question}
                maxLength={500}
                placeholder="What slipped last month and why?"
                onChange={(e) => setQuestion(e.target.value)}
              />
            )}
          </Field>
          <Button
            type="submit"
            variant="primary"
            icon={<SendHorizontal />}
            loading={ask.isPending}
            className="ask__submit"
          >
            Ask
          </Button>
        </form>
        <div className="row" role="group" aria-label="Date range">
          {[
            ['30', 'Last 30 days'],
            ['90', 'Last 90 days'],
            ['all', 'All time'],
            ['custom', 'Custom'],
          ].map(([v, l]) => (
            <FilterChip key={v} pressed={range === v} onToggle={() => setRange(v)}>
              {l}
            </FilterChip>
          ))}
        </div>
        {range === 'custom' && (
          <div className="row">
            <Field label="From">
              {(p) => (
                <Input
                  {...p}
                  type="date"
                  value={custom.from}
                  onChange={(e) => setCustom({ ...custom, from: e.target.value })}
                />
              )}
            </Field>
            <Field label="To">
              {(p) => (
                <Input
                  {...p}
                  type="date"
                  value={custom.to}
                  onChange={(e) => setCustom({ ...custom, to: e.target.value })}
                />
              )}
            </Field>
          </div>
        )}
      </section>

      {ask.isPending && (
        <section className="glass panel stack" aria-busy="true" aria-live="polite">
          <p className="row small muted">
            <Sparkles aria-hidden="true" className="icon-inline" /> Searching {project.name} tasks…
          </p>
          <Skeleton height={18} width="90%" />
          <Skeleton height={18} width="75%" />
          <Skeleton height={18} width="60%" />
        </section>
      )}

      {!ask.isPending && !latest && (
        <section className="glass panel">
          <EmptyState
            icon={<MessageSquareText aria-hidden="true" />}
            title="Ask about decisions, delays or what happened in a period"
          >
            <div className="row ask__examples">
              {EXAMPLES.map((x) => (
                <button
                  key={x}
                  type="button"
                  className="chip filter-chip"
                  onClick={() => {
                    setQuestion(x);
                    submit(null, x);
                  }}
                >
                  {x}
                </button>
              ))}
            </div>
          </EmptyState>
        </section>
      )}

      {!ask.isPending && latest && <Answer entry={latest} projectKey={project.key} />}

      {history.length > 1 && (
        <section className="glass panel" aria-labelledby="history-h">
          <h2 id="history-h" className="panel__title">
            Earlier this session
          </h2>
          <ul className="stack">
            {history.slice(1).map((h) => (
              <li key={h.id}>
                <details className="history">
                  <summary>{h.q}</summary>
                  <Answer entry={h} projectKey={project.key} compact />
                </details>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function Answer({ entry, projectKey, compact }) {
  const { r } = entry;
  return (
    <section className={compact ? 'stack' : 'glass panel stack'} aria-label="Answer">
      {!compact && <p className="small muted">{entry.q}</p>}
      {r.degraded ? (
        <Notice kind="info" title="AI answer unavailable">
          {DEGRADED[r.reason] || DEGRADED.LLM_ERROR}
          {r.retryAfter ? ` Try again in about ${r.retryAfter} s.` : ''}
        </Notice>
      ) : (
        <div className="answer">
          <h3 className="row">
            <Sparkles aria-hidden="true" className="icon-inline" /> Answer
          </h3>
          <Markdown>{r.answer}</Markdown>
          {r.reason === 'NO_CONTEXT' ? (
            <p className="small muted">No tasks matched. Try other words or a wider date range.</p>
          ) : r.grounded ? (
            <p className="small muted">AI-generated from your project&apos;s tasks. Check the sources.</p>
          ) : (
            <Notice kind="warning">
              This answer may not be based on your tasks. Check the sources below.
            </Notice>
          )}
        </div>
      )}
      {r.sources.length > 0 && (
        <>
          <h3>Sources</h3>
          <ul className="sources">
            {r.sources.map((s, i) => (
              <li key={`${s.taskKey}-${i}`}>
                <Link
                  to={`/projects/${projectKey}/tasks/${s.taskKey}`}
                  className="source clay clay--interactive"
                >
                  <span className="row small">
                    <span className="mono">{s.taskKey}</span>
                    <StatusPill status={s.status} />
                    <span className="muted">
                      {s.sourceType === 'COMMENT' ? 'comment' : 'task'} · {Math.round(s.similarity * 100)}%
                      match
                    </span>
                  </span>
                  <strong>{s.title}</strong>
                  <span className="small muted source__snippet">{s.snippet}</span>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
