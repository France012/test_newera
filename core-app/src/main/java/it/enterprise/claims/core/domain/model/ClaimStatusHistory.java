package it.enterprise.claims.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the append-only history of a claim ({@code coreapp.claim_status_history}).
 *
 * <p>Since TK-64FBC8 the row also records <em>who</em> performed the transition ({@code actorId});
 * the actor name is a denormalised value resolved by the persistence adapter, exactly like {@code
 * policyNumber} on the claim. Both are {@code null} for rows written before the ticket.
 *
 * @param id row identifier
 * @param claimId claim the transition belongs to
 * @param fromStatus previous state, {@code null} for the opening transition
 * @param toStatus state the claim moved to
 * @param note optional free-text reason, may be {@code null}
 * @param changedAt timestamp of the transition
 * @param actorId actor who performed the transition, may be {@code null}
 * @param actorDisplayName display name of that actor (resolved), may be {@code null}
 */
public record ClaimStatusHistory(
    UUID id,
    UUID claimId,
    ClaimStatus fromStatus,
    ClaimStatus toStatus,
    String note,
    OffsetDateTime changedAt,
    UUID actorId,
    String actorDisplayName) {

  /**
   * Backwards-compatible constructor for a transition with no recorded author.
   *
   * @param id row identifier
   * @param claimId claim the transition belongs to
   * @param fromStatus previous state, {@code null} for the opening transition
   * @param toStatus state the claim moved to
   * @param note optional free-text reason
   * @param changedAt timestamp of the transition
   */
  public ClaimStatusHistory(
      UUID id,
      UUID claimId,
      ClaimStatus fromStatus,
      ClaimStatus toStatus,
      String note,
      OffsetDateTime changedAt) {
    this(id, claimId, fromStatus, toStatus, note, changedAt, null, null);
  }

  /** Compact constructor: {@code fromStatus} is intentionally allowed to be {@code null}. */
  public ClaimStatusHistory {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(claimId, "claimId is required");
    Objects.requireNonNull(toStatus, "toStatus is required");
    Objects.requireNonNull(changedAt, "changedAt is required");
  }
}
