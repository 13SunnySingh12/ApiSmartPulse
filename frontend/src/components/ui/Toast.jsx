import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';
import { IconButton } from './Button.jsx';

const ToastContext = createContext(null);
const ICONS = { success: CheckCircle2, error: AlertCircle, info: Info };

/**
 * Glass toasts (§4.6.5): bottom-right desktop / top mobile, auto-dismiss after 5 s, errors persist
 * until closed. Announced politely; errors assertively.
 */
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const show = useCallback(
    (kind, message, opts = {}) => {
      const id = nextId.current++;
      setToasts((t) => [...t.slice(-3), { id, kind, message, ...opts }]);
      if (kind !== 'error') setTimeout(() => dismiss(id), opts.duration ?? 5000);
      return id;
    },
    [dismiss],
  );
  const api = useMemo(
    () => ({
      success: (m, o) => show('success', m, o),
      error: (m, o) => show('error', m, o),
      info: (m, o) => show('info', m, o),
    }),
    [show],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" className="visually-hidden">
        {toasts
          .filter((t) => t.kind !== 'error')
          .map((t) => t.message)
          .join('. ')}
      </div>
      <div aria-live="assertive" className="visually-hidden">
        {toasts
          .filter((t) => t.kind === 'error')
          .map((t) => t.message)
          .join('. ')}
      </div>
      <ul className="toasts" aria-label="Notifications">
        {toasts.map((t) => {
          const Icon = ICONS[t.kind];
          return (
            <li key={t.id} className={`toast toast--${t.kind} glass glass--raised`}>
              <Icon className="toast__icon" aria-hidden="true" />
              <div className="toast__body">
                <p>{t.message}</p>
                {t.requestId && <p className="micro muted">Request ID {t.requestId}</p>}
                {t.action && (
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => {
                      t.action.onClick();
                      dismiss(t.id);
                    }}
                  >
                    {t.action.label}
                  </button>
                )}
              </div>
              <IconButton
                label="Dismiss notification"
                size="sm"
                variant="ghost"
                onClick={() => dismiss(t.id)}
              >
                <X />
              </IconButton>
            </li>
          );
        })}
      </ul>
    </ToastContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useToast() {
  return useContext(ToastContext);
}
