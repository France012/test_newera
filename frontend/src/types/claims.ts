/**
 * Domain types of the claims UI.
 *
 * They mirror the BFF contract (blueprint §2.5) one-to-one with the Java records in
 * `bff/src/main/java/it/enterprise/claims/bff/web/dto/*`:
 *   - `ClaimSummaryResponse`   -> {@link ClaimSummary}
 *   - `DocumentSummaryResponse`-> {@link DocumentSummary}
 *   - `ClaimDetailResponse`    -> {@link ClaimDetail}
 *   - `DashboardSummaryResponse` -> {@link DashboardSummary}
 *
 * `status` is a plain string on the wire (the BFF deliberately does not re-implement the
 * state machine), so the UI keeps the union type locally and degrades gracefully on an
 * unknown value instead of crashing on a status added by core-app later.
 */

/** Lifecycle order, mirrored from `core-app` `ClaimStatus`. */
export const CLAIM_STATUSES = [
  'DRAFT',
  'SUBMITTED',
  'UNDER_REVIEW',
  'INTEGRATION_REQUESTED',
  'APPROVED',
  'REJECTED',
  'CLOSED',
] as const;

export type ClaimStatus = (typeof CLAIM_STATUSES)[number];

/** Statuses that still require an operator action - same set the BFF sums into `totalOpen`. */
export const OPEN_STATUSES: readonly ClaimStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'UNDER_REVIEW',
  'INTEGRATION_REQUESTED',
];

/** Human readable Italian labels for the badges and the filter. */
export const STATUS_LABELS: Record<ClaimStatus, string> = {
  DRAFT: 'Bozza',
  SUBMITTED: 'Inviata',
  UNDER_REVIEW: 'In lavorazione',
  INTEGRATION_REQUESTED: 'Integrazione richiesta',
  APPROVED: 'Approvata',
  REJECTED: 'Respinta',
  CLOSED: 'Chiusa',
};

/**
 * Allowed transitions, mirrored from blueprint §2.3.
 * The backend stays authoritative: this map only drives the UI affordances.
 */
export const ALLOWED_TRANSITIONS: Record<ClaimStatus, readonly ClaimStatus[]> = {
  DRAFT: ['SUBMITTED'],
  SUBMITTED: ['UNDER_REVIEW'],
  UNDER_REVIEW: ['INTEGRATION_REQUESTED', 'APPROVED', 'REJECTED'],
  INTEGRATION_REQUESTED: ['UNDER_REVIEW'],
  APPROVED: ['CLOSED'],
  REJECTED: ['CLOSED'],
  CLOSED: [],
};

/** Claim as returned by `GET /bff/v1/claims` and inside {@link ClaimDetail}. */
export interface ClaimSummary {
  id: string;
  claimNumber: string;
  policyNumber: string;
  customerId: string;
  claimType: string;
  status: string;
  description: string | null;
  incidentDate: string;
  openedAt: string;
  updatedAt: string;
  documentIds: string[];
}

/** Document as returned by the BFF aggregation (never the storage key). */
export interface DocumentSummary {
  id: string;
  claimId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  documentType: string;
  checksumSha256: string;
  uploadedAt: string;
}

/** `GET /bff/v1/claims/{id}` - claim plus its documents in one round trip. */
export interface ClaimDetail {
  claim: ClaimSummary;
  documents: DocumentSummary[];
}

/** `GET /bff/v1/dashboard/summary` */
export interface DashboardSummary {
  totalOpen: number;
  byStatus: Record<string, number>;
}

/** Narrows an arbitrary wire value to a known status. */
export function isKnownStatus(value: string): value is ClaimStatus {
  return (CLAIM_STATUSES as readonly string[]).includes(value);
}

/** Label for a status, falling back to the raw value for statuses the UI does not know yet. */
export function statusLabel(status: string): string {
  return isKnownStatus(status) ? STATUS_LABELS[status] : status;
}

/** Whether a claim still needs work. */
export function isOpenStatus(status: string): boolean {
  return (OPEN_STATUSES as readonly string[]).includes(status);
}
