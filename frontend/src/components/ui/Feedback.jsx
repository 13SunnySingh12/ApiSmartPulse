import { AlertCircle, AlertTriangle, CheckCircle2, Info, RefreshCw, WifiOff } from 'lucide-react';
import { Button } from './Button.jsx';
import { problem as toProblem } from '../../api/client.js';

const ICONS = {
  error: AlertCircle,
  warning: AlertTriangle,
  success: CheckCircle2,
  info: Info,
  offline: WifiOff,
};

/** Inline notice (DS-026). `info` is the neutral style used for degraded AI. */
export function Notice({ kind = 'info', title, children, actions, role }) {
  const Icon = ICONS[kind] || Info;
  return (
    <div
      className={`notice notice--${kind === 'offline' ? 'warning' : kind}`}
      role={role || (kind === 'error' ? 'alert' : 'status')}
    >
      <Icon className="notice__icon" aria-hidden="true" />
      <div className="notice__body">
        {title && <strong>{title}</strong>}
        {children && <div>{children}</div>}
        {actions && <div className="notice__actions">{actions}</div>}
      </div>
    </div>
  );
}

/** Recoverable error with plain language, Retry, and the request ID for support. */
export function ErrorNotice({ error, onRetry, title = 'Something went wrong' }) {
  const p = error?.code ? error : toProblem(error);
  return (
    <Notice
      kind="error"
      title={title}
      actions={
        onRetry && (
          <Button size="sm" icon={<RefreshCw />} onClick={onRetry}>
            Retry
          </Button>
        )
      }
    >
      <p>{p.detail}</p>
      {p.requestId && <p className="micro">Request ID {p.requestId}</p>}
    </Notice>
  );
}

/** Empty state: original line illustration, one sentence, one action (§4.9). */
export function EmptyState({ title, children, action, icon }) {
  return (
    <div className="empty">
      {icon || <EmptyArt />}
      {title && <p className="empty__title">{title}</p>}
      {children && <div>{children}</div>}
      {action}
    </div>
  );
}

function EmptyArt() {
  return (
    <svg viewBox="0 0 72 72" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <rect x="10" y="16" width="52" height="40" rx="10" />
      <path d="M22 30h28M22 40h18" strokeLinecap="round" />
      <circle cx="54" cy="18" r="8" fill="var(--clay-surface)" />
      <path d="M54 14v8M50 18h8" strokeLinecap="round" />
    </svg>
  );
}

export function Skeleton({ height = 16, width = '100%', radius }) {
  return <span className="skeleton" style={{ height, width, borderRadius: radius }} aria-hidden="true" />;
}

export function SkeletonCards({ count = 3, height = 120 }) {
  return (
    <div className="stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => (
        <Skeleton key={i} height={height} radius="var(--radius-lg)" />
      ))}
    </div>
  );
}
