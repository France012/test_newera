package it.enterprise.claims.core.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of a claim.
 *
 * <p>This enum is the single source of truth for the {@code coreapp.claims.status} and {@code
 * coreapp.claim_status_history.*_status} columns: it is mapped with
 * {@code @Enumerated(EnumType.STRING)} and mirrored by the {@code ck_claims_status} CHECK
 * constraint in {@code V1__init_claims.sql}. Adding/removing a value requires BOTH an enum change
 * and a new Flyway migration.
 *
 * <p>Allowed transitions (blueprint section 2.3): DRAFT to SUBMITTED, SUBMITTED to UNDER_REVIEW,
 * UNDER_REVIEW to INTEGRATION_REQUESTED/APPROVED/REJECTED, INTEGRATION_REQUESTED to UNDER_REVIEW,
 * APPROVED/REJECTED to CLOSED. Any other transition must be rejected with HTTP 409.
 */
public enum ClaimStatus {
  DRAFT,
  SUBMITTED,
  UNDER_REVIEW,
  INTEGRATION_REQUESTED,
  APPROVED,
  REJECTED,
  CLOSED;

  /**
   * States reachable from this one in a single transition.
   *
   * @return an immutable-by-convention set, never {@code null} (empty for terminal states)
   */
  public Set<ClaimStatus> allowedTransitions() {
    return switch (this) {
      case DRAFT -> EnumSet.of(SUBMITTED);
      case SUBMITTED -> EnumSet.of(UNDER_REVIEW);
      case UNDER_REVIEW -> EnumSet.of(INTEGRATION_REQUESTED, APPROVED, REJECTED);
      case INTEGRATION_REQUESTED -> EnumSet.of(UNDER_REVIEW);
      case APPROVED, REJECTED -> EnumSet.of(CLOSED);
      case CLOSED -> EnumSet.noneOf(ClaimStatus.class);
    };
  }

  /**
   * @param target requested target state, may be {@code null}
   * @return {@code true} only if the transition is allowed by the state machine
   */
  public boolean canTransitionTo(ClaimStatus target) {
    return target != null && allowedTransitions().contains(target);
  }

  /**
   * @return {@code true} if no further transition is possible
   */
  public boolean isTerminal() {
    return this == CLOSED;
  }
}
