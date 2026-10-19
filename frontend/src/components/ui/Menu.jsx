import * as DM from '@radix-ui/react-dropdown-menu';
import * as TT from '@radix-ui/react-tooltip';

/** Glass-2 dropdown menu with roving focus and arrow-key navigation (Radix). */
export function Menu({ trigger, children, align = 'end', label }) {
  return (
    <DM.Root>
      <DM.Trigger asChild>{trigger}</DM.Trigger>
      <DM.Portal>
        <DM.Content className="menu glass glass--raised" align={align} sideOffset={6} aria-label={label}>
          {children}
        </DM.Content>
      </DM.Portal>
    </DM.Root>
  );
}

export function MenuItem({ icon, children, onSelect, disabled }) {
  return (
    <DM.Item className="menu__item" onSelect={onSelect} disabled={disabled}>
      {icon}
      {children}
    </DM.Item>
  );
}

export const MenuLabel = ({ children }) => <DM.Label className="menu__label">{children}</DM.Label>;
export const MenuSeparator = () => <DM.Separator className="menu__sep" />;

/** Glass-2 tooltip on hover and focus after 400 ms; instant for neighbours once one is open. */
export function TooltipProvider({ children }) {
  return (
    <TT.Provider delayDuration={400} skipDelayDuration={300}>
      {children}
    </TT.Provider>
  );
}

export function Tooltip({ content, children }) {
  if (!content) return children;
  return (
    <TT.Root>
      <TT.Trigger asChild>{children}</TT.Trigger>
      <TT.Portal>
        <TT.Content className="tooltip glass glass--raised" sideOffset={6}>
          {content}
        </TT.Content>
      </TT.Portal>
    </TT.Root>
  );
}
