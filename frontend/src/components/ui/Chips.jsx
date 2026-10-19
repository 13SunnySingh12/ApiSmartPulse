import {
  AlertTriangle,
  ArrowDown,
  ArrowRight,
  ArrowUp,
  CheckCircle2,
  ChevronsUp,
  CircleDashed,
  Clock,
  Lock,
  ShieldCheck,
  Sparkles,
  Wand2,
} from 'lucide-react';

/** Status is always text + icon + colour (DS-004, DS-035). */
const RISK = {
  HIGH: { cls: 'chip--high', icon: AlertTriangle, text: 'High risk' },
  MEDIUM: { cls: 'chip--medium', icon: Clock, text: 'Medium risk' },
  LOW: { cls: 'chip--low', icon: ShieldCheck, text: 'Low risk' },
  NA: { cls: '', icon: CircleDashed, text: 'Risk N/A' },
};

export function RiskBadge({ prediction, showPct }) {
  if (!prediction || prediction.status !== 'OK') {
    return (
      <span className="chip chip--pending" title="Prediction is being calculated">
        <CircleDashed aria-hidden="true" /> Pending
      </span>
    );
  }
  const r = RISK[prediction.riskLevel] || RISK.NA;
  const Icon = r.icon;
  const pct =
    showPct && prediction.overdueProbability != null
      ? ` · ${Math.round(prediction.overdueProbability * 100)}%`
      : '';
  return (
    <span className={`chip ${r.cls}`}>
      <Icon aria-hidden="true" />
      {r.text}
      {pct}
    </span>
  );
}

const PRIORITY = {
  URGENT: { icon: ChevronsUp, text: 'Urgent' },
  HIGH: { icon: ArrowUp, text: 'High' },
  MEDIUM: { icon: ArrowRight, text: 'Medium' },
  LOW: { icon: ArrowDown, text: 'Low' },
};

export function PriorityChip({ priority }) {
  const p = PRIORITY[priority] || PRIORITY.MEDIUM;
  const Icon = p.icon;
  return (
    <span className={`chip prio--${priority.toLowerCase()}`}>
      <Icon aria-hidden="true" />
      {p.text}
    </span>
  );
}

export const PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
export const priorityLabel = (p) => PRIORITY[p]?.text ?? p;

/** "AI" for model output, "Rule" for heuristics (DS-005). */
export function AiTag({ source }) {
  const model = source === 'MODEL';
  return (
    <span className="chip chip--ai" title={model ? 'Predicted by a trained model' : 'Estimated by a rule'}>
      {model ? <Sparkles aria-hidden="true" /> : <Wand2 aria-hidden="true" />}
      {model ? 'AI' : 'Rule'}
    </span>
  );
}

export function LabelChip({ label }) {
  return (
    <span className="chip">
      <span className={`chip__dot dot--${label.color}`} aria-hidden="true" />
      {label.name}
    </span>
  );
}

const STATUS = {
  TODO: { cls: '', text: 'To Do', icon: CircleDashed },
  IN_PROGRESS: { cls: 'chip--progress', text: 'In Progress', icon: Clock },
  DONE: { cls: 'chip--done', text: 'Done', icon: CheckCircle2 },
};

export const statusLabel = (s) => STATUS[s]?.text ?? s;

export function StatusPill({ status }) {
  const s = STATUS[status] || STATUS.TODO;
  const Icon = s.icon;
  return (
    <span className={`chip ${s.cls}`}>
      <Icon aria-hidden="true" />
      {s.text}
    </span>
  );
}

export function BlockedChip() {
  return (
    <span className="chip chip--high">
      <Lock aria-hidden="true" /> Blocked
    </span>
  );
}

export function FilterChip({ pressed, onToggle, children }) {
  return (
    <button type="button" className="chip filter-chip" aria-pressed={pressed} onClick={onToggle}>
      {pressed && <CheckCircle2 aria-hidden="true" />}
      {children}
    </button>
  );
}

export function Avatar({ name, size }) {
  const initials = (name || '?')
    .replace(' (deactivated)', '')
    .split(/\s+/)
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <span className={`avatar ${size === 'lg' ? 'avatar--lg' : ''}`} title={name} aria-hidden="true">
      {initials}
    </span>
  );
}
