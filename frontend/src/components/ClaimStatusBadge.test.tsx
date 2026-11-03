import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ClaimStatusBadge } from './ClaimStatusBadge';

describe('ClaimStatusBadge', () => {
  it('renders the Italian label of a known status', () => {
    render(<ClaimStatusBadge status="UNDER_REVIEW" />);

    expect(screen.getByText('In lavorazione')).toBeInTheDocument();
  });

  it('applies the tone of the status so the colour encodes the state', () => {
    render(<ClaimStatusBadge status="APPROVED" />);

    const badge = screen.getByText('Approvata');
    expect(badge.className).toContain('badge--success');
    expect(badge.getAttribute('data-status')).toBe('APPROVED');
  });

  it('degrades gracefully on a status the UI does not know yet', () => {
    render(<ClaimStatusBadge status="ON_HOLD" />);

    const badge = screen.getByText('ON_HOLD');
    expect(badge.className).toContain('badge--neutral');
  });
});
