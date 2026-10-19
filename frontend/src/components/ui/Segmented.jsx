import { useRef } from 'react';

/**
 * Clay segmented control as an ARIA radio group: inset track, raised thumb. Arrow keys move the
 * selection (no animation on keyboard input).
 */
export function Segmented({ label, options, value, onChange, className = '' }) {
  const refs = useRef([]);
  const index = options.findIndex((o) => o.value === value);
  const move = (delta) => {
    const next = (index + delta + options.length) % options.length;
    onChange(options[next].value);
    refs.current[next]?.focus();
  };
  return (
    <div role="radiogroup" aria-label={label} className={`segmented ${className}`}>
      {options.map((o, i) => (
        <button
          key={o.value}
          ref={(el) => (refs.current[i] = el)}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          tabIndex={o.value === value ? 0 : -1}
          className="segmented__item"
          onClick={() => onChange(o.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
              e.preventDefault();
              move(1);
            } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
              e.preventDefault();
              move(-1);
            }
          }}
          aria-label={o.ariaLabel}
          title={o.ariaLabel}
        >
          {o.icon}
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** Clay inset track + flat fill. Value is a percentage; status adds a colour, text says it too. */
export function Bar({ value, status, label }) {
  const cls = status === 'OVER' ? 'bar__fill--over' : status === 'UNDER' ? 'bar__fill--under' : '';
  return (
    <div
      className="bar"
      role="meter"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.min(100, value)}
      aria-label={label}
    >
      <div className={`bar__fill ${cls}`} style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
    </div>
  );
}
