import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button } from './Button.jsx';
import { RiskBadge, PriorityChip } from './Chips.jsx';
import { Field, Input } from './Field.jsx';
import { Markdown } from './Markdown.jsx';
import { expectNoSeriousA11yViolations } from '../../test/utils.jsx';

describe('Button (QA-FE-01)', () => {
  it('loading sets aria-busy and blocks clicks', async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Save
      </Button>,
    );
    const btn = screen.getByRole('button', { name: /save/i });
    expect(btn).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(btn);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('disabled sets aria-disabled, stays focusable and ignores clicks', async () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Delete
      </Button>,
    );
    const btn = screen.getByRole('button', { name: 'Delete' });
    expect(btn).toHaveAttribute('aria-disabled', 'true');
    btn.focus();
    expect(btn).toHaveFocus();
    await userEvent.click(btn);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('RiskBadge (QA-FE-04)', () => {
  it('shows text and icon, never colour alone', () => {
    const { container } = render(
      <RiskBadge prediction={{ status: 'OK', riskLevel: 'HIGH', overdueProbability: 0.72 }} showPct />,
    );
    expect(screen.getByText(/High risk/)).toHaveTextContent('High risk · 72%');
    expect(container.querySelector('svg')).toBeInTheDocument();
  });

  it('shows Pending when there is no prediction yet', () => {
    render(<RiskBadge prediction={{ status: 'PENDING' }} />);
    expect(screen.getByText('Pending')).toBeInTheDocument();
  });
});

describe('Field', () => {
  it('links label, help and error to the control', async () => {
    const { container } = render(
      <Field label="Title" help="3 to 200 characters" error="Title is required">
        {(p) => <Input {...p} />}
      </Field>,
    );
    const input = screen.getByLabelText('Title');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Title is required 3 to 200 characters');
    await expectNoSeriousA11yViolations(container);
  });
});

describe('Markdown (QA-SEC-08)', () => {
  it('drops raw HTML and javascript: links', () => {
    const { container } = render(
      <Markdown>
        {'<img src=x onerror=alert(1)> [x](javascript:alert(1)) [ok](https://example.com)'}
      </Markdown>,
    );
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('a[href^="javascript"]')).toBeNull();
    const ok = screen.getByRole('link', { name: 'ok' });
    expect(ok).toHaveAttribute('rel', 'noopener noreferrer');
  });
});

describe('UI kit accessibility (QA-A11Y-01)', () => {
  it('chips and buttons have no serious axe violations', async () => {
    const { container } = render(
      <main>
        <PriorityChip priority="URGENT" />
        <RiskBadge prediction={{ status: 'OK', riskLevel: 'LOW' }} />
        <Button variant="primary">Create</Button>
      </main>,
    );
    await expectNoSeriousA11yViolations(container);
  });
});
