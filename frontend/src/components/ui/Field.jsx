import { useId } from 'react';
import { AlertCircle } from 'lucide-react';

/**
 * Visible label above, helper below, error with icon below (DS-021, DS-034). The control receives
 * id / aria-describedby / aria-invalid through the render prop.
 */
export function Field({ label, help, error, children, className = '', id: fixedId }) {
  const generated = useId();
  const id = fixedId || generated;
  const helpId = help ? `${id}-help` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const control = children({
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': [errorId, helpId].filter(Boolean).join(' ') || undefined,
  });
  return (
    <div className={`field ${className}`}>
      <label className="field__label" htmlFor={id}>
        {label}
      </label>
      {control}
      {error && (
        <p className="field__error" id={errorId}>
          <AlertCircle aria-hidden="true" />
          {error}
        </p>
      )}
      {help && (
        <p className="field__help" id={helpId}>
          {help}
        </p>
      )}
    </div>
  );
}

export function Input(props) {
  return <input className="input" {...props} />;
}

export function Textarea(props) {
  return <textarea className="input" {...props} />;
}

export function Select({ children, ...props }) {
  return (
    <select className="input" {...props}>
      {children}
    </select>
  );
}

/** Maps a problem's field errors to { field: message }. */
export function fieldErrors(p) {
  return Object.fromEntries((p?.errors || []).map((e) => [e.field, e.message]));
}
