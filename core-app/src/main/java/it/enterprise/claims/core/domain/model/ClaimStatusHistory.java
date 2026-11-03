package it.enterprise.claims.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the append-only audit trail of a claim ({@code coreapp.claim_status_history}).
 *
 * @param id row identifier
 * @param claimId claim the transition belongs to
 * @param fromStatus previous state, {@code null} for the opening transition
 * @param toStatus state the claim moved to
 * @param note optional free-text reason, may be {@code null}
 * @param changedAt timestamp of the transition
 */
public record ClaimStatusHistory(
    UUID id,
    UUID claimId,
    ClaimStatus fromStatus,
    ClaimStatus toStatus,
    String note,
    OffsetDateTime changedAt) {

  /** Compact constructor: {@code fromStatus} is intentionally allowed to be {@code null}. */
  public ClaimStatusHistory {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(claimId, "claimId is required");
    Objects.requireNonNull(toStatus, "toStatus is required");
    Objects.requireNonNull(changedAt, "changedAt is required");
  }
}
