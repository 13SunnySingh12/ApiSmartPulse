import { forwardRef } from 'react';
import { useOnline } from '../../hooks/useOnline.js';

/**
 * Clay button (DS-020). Disabled uses aria-disabled so it stays focusable and can explain itself;
 * loading locks width, sets aria-busy and ignores clicks. Offline disables network actions.
 */
export const Button = forwardRef(function Button(
  {
    variant = 'secondary',
    size,
    block,
    loading,
    disabled,
    offlineOk,
    icon,
    children,
    className = '',
    onClick,
    type = 'button',
    title,
    ...rest
  },
  ref,
) {
  const online = useOnline();
  const blocked = disabled || (!online && !offlineOk);
  const classes = ['btn', `btn--${variant}`, size === 'sm' && 'btn--sm', block && 'btn--block', className]
    .filter(Boolean)
    .join(' ');
  return (
    <button
      ref={ref}
      type={type}
      className={classes}
      aria-disabled={blocked || undefined}
      aria-busy={loading || undefined}
      title={!online && !offlineOk ? "You're offline" : title}
      onClick={(e) => {
        if (blocked || loading) {
          e.preventDefault();
          return;
        }
        onClick?.(e);
      }}
      {...rest}
    >
      <span className="btn__label">
        {icon}
        {children}
      </span>
      {loading && (
        <span className="btn__busy">
          <span className="spinner" aria-hidden="true" />
          <span className="visually-hidden">Working…</span>
        </span>
      )}
    </button>
  );
});

/** Circular clay icon button; a label is mandatory for accessibility. */
export const IconButton = forwardRef(function IconButton({ label, children, className = '', ...rest }, ref) {
  return (
    <Button
      ref={ref}
      aria-label={label}
      title={label}
      className={`icon-btn ${className}`}
      offlineOk
      {...rest}
    >
      {children}
    </Button>
  );
});
