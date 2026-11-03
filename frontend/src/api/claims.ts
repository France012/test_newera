/**
 * Typed access to the BFF endpoints (blueprint §2.5).
 *
 * Only the three read endpoints that exist in phase 1 are wrapped: the BFF is deliberately
 * read-only, mutations stay on the owning service. Paths are literal so a contract change
 * shows up as a single, reviewable diff.
 */
import { apiGet } from './client';
import type { ClaimDetail, ClaimSummary, DashboardSummary, ClaimStatus } from '../types/claims';

/** BFF base path, kept in one place. */
export const BFF_BASE = '/bff/v1';

/** Default page size, matching the BFF's own default. */
export const DEFAULT_PAGE_SIZE = 20;

export interface ListClaimsParams {
  /** Optional status filter; `''`, `null` and `undefined` mean "all statuses". */
  status?: ClaimStatus | string | null;
  page?: number;
  size?: number;
  signal?: AbortSignal;
}

/**
 * `GET /bff/v1/claims` - returns a plain array.
 *
 * The BFF exposes only the `content` of core-app's page (see `BffClaimController`), so there
 * is no total count on the wire: the UI derives "there may be more" from
 * `items.length === size`. Kept honest instead of inventing a count the API does not return.
 *
 * @param params optional status filter and pagination
 * @returns the claims of the requested page, in the order the BFF returned them
 */
export function listClaims(params: ListClaimsParams = {}): Promise<ClaimSummary[]> {
  const { status, page = 0, size = DEFAULT_PAGE_SIZE, signal } = params;
  return apiGet<ClaimSummary[]>(`${BFF_BASE}/claims`, {
    query: { status: status ?? undefined, page, size },
    signal,
  });
}

/**
 * `GET /bff/v1/claims/{id}` - claim plus its documents in a single round trip.
 *
 * @param claimId claim identifier (UUID)
 */
export function getClaimDetail(claimId: string, signal?: AbortSignal): Promise<ClaimDetail> {
  return apiGet<ClaimDetail>(`${BFF_BASE}/claims/${encodeURIComponent(claimId)}`, { signal });
}

/**
 * `GET /bff/v1/dashboard/summary` - open-claim total plus counters per status.
 *
 * @param signal optional cancellation signal
 */
export function getDashboardSummary(signal?: AbortSignal): Promise<DashboardSummary> {
  return apiGet<DashboardSummary>(`${BFF_BASE}/dashboard/summary`, { signal });
}
