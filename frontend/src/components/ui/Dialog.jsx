import * as RD from '@radix-ui/react-dialog';
import { AlertTriangle, X } from 'lucide-react';
import { Button, IconButton } from './Button.jsx';

/**
 * Glass dialog on a scrim (§4.6.5): focus trapped, Esc closes (unless `busy`), focus returns to
 * the trigger. Becomes a bottom sheet on mobile.
 */
export function Dialog({ open, onOpenChange, title, description, children, busy, className = '' }) {
  return (
    <RD.Root open={open} onOpenChange={(v) => (!busy || v) && onOpenChange(v)}>
      <RD.Portal>
        <RD.Overlay className="scrim" />
        <RD.Content
          className={`dialog glass glass--strong ${className}`}
          onEscapeKeyDown={(e) => busy && e.preventDefault()}
          onPointerDownOutside={(e) => busy && e.preventDefault()}
        >
          <RD.Title className="dialog__title">{title}</RD.Title>
          {description ? (
            <RD.Description className="dialog__desc">{description}</RD.Description>
          ) : (
            <RD.Description className="visually-hidden">{title}</RD.Description>
          )}
          {children}
          <RD.Close asChild>
            <IconButton label="Close" size="sm" className="dialog__close" disabled={busy}>
              <X />
            </IconButton>
          </RD.Close>
        </RD.Content>
      </RD.Portal>
    </RD.Root>
  );
}

/** Destructive confirmation that names the object and uses the verb as the button label. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  onConfirm,
  busy,
  danger = true,
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title} description={description} busy={busy}>
      <div className="dialog__actions">
        <Button onClick={() => onOpenChange(false)} disabled={busy} offlineOk>
          Cancel
        </Button>
        <Button
          variant={danger ? 'danger' : 'primary'}
          icon={danger ? <AlertTriangle /> : undefined}
          onClick={onConfirm}
          loading={busy}
        >
          {confirmLabel}
        </Button>
      </div>
    </Dialog>
  );
}

/** Right-side glass drawer (task detail); full screen on mobile. */
export function Drawer({ open, onOpenChange, title, children }) {
  return (
    <RD.Root open={open} onOpenChange={onOpenChange}>
      <RD.Portal>
        <RD.Overlay className="scrim" />
        <RD.Content className="drawer glass glass--strong" aria-describedby={undefined}>
          <RD.Title className="visually-hidden">{title}</RD.Title>
          {children}
        </RD.Content>
      </RD.Portal>
    </RD.Root>
  );
}

export const DialogClose = RD.Close;
