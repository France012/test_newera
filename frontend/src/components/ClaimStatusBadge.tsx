import { isKnownStatus, statusLabel, type ClaimStatus } from '../types/claims';

/**
 * Visual tone per lifecycle state. Statuses the UI does not know fall back to `neutral`
 * and are rendered with their raw value, so a new backend state never breaks the page.
 */
const TONES: Record<ClaimStatus, string> = {
  DRAFT: 'neutral',
  SUBMITTED: 'info',
  UNDER_REVIEW: 'warning',
  INTEGRATION_REQUESTED: 'attention',
  APPRAISAL: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  SETTLEMENT: 'info',
  CLOSED: 'muted',
  ARCHIVED: 'muted',
};

export interface ClaimStatusBadgeProps {
  /** Status as received from the API - intentionally a loose string. */
  status: string;
  size?: 'sm' | 'md';
}

/**
 * Pill showing the lifecycle state of a claim.
 *
 * The dot carries the colour and the text carries the meaning, so the badge stays
 * readable for colour-blind users and in greyscale printouts.
 */
export function ClaimStatusBadge({ status, size = 'md' }: ClaimStatusBadgeProps) {
  const tone = isKnownStatus(status) ? TONES[status] : 'neutral';
  const label = statusLabel(status);
  return (
    <span
      className={`badge badge--${tone} badge--${size}`}
      data-status={status}
      title={`Stato: ${label}`}
    >
      <span className="badge__dot" aria-hidden="true" />
      {label}
    </span>
  );
}
