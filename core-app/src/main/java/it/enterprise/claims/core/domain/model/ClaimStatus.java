package it.enterprise.claims.core.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of a claim (Italian claims domain: <em>stato della pratica</em>).
 *
 * <p>This enum is the single source of truth for the {@code coreapp.claims.status} and {@code
 * coreapp.claim_status_history.*_status} columns: it is mapped with
 * {@code @Enumerated(EnumType.STRING)} and mirrored by the CHECK constraints declared in {@code
 * V1__init_claims.sql} plus {@code V2__claim_workflow_actors_audit.sql}, and by the seed of {@code
 * coreapp.claim_status_transition}. Adding/removing a value requires BOTH an enum change and a new
 * Flyway migration (docs/db/README.md §7.5).
 *
 * <p>Declaration order is the <strong>lifecycle order</strong>: it drives UI ordering, dashboard
 * counters and the documentation, so do not reorder it.
 *
 * <p>Ticket (IT) to persisted value mapping:
 *
 * <ul>
 *   <li>{@code DRAFT} — aperta (in compilazione)
 *   <li>{@code SUBMITTED} — inviata / presa in carico
 *   <li>{@code UNDER_REVIEW} — in istruttoria
 *   <li>{@code INTEGRATION_REQUESTED} — istruttoria sospesa, in attesa di integrazione
 *   <li>{@code APPRAISAL} — perizia
 *   <li>{@code APPROVED} — delibera favorevole
 *   <li>{@code REJECTED} — respinta
 *   <li>{@code SETTLEMENT} — liquidazione
 *   <li>{@code CLOSED} — chiusa
 *   <li>{@code ARCHIVED} — archiviata (terminale)
 * </ul>
 *
 * <p>Allowed transitions are declared in {@link #allowedTransitions()} and are enforced twice: by
 * the application (HTTP 409 before the write) and by the database (composite foreign key from
 * {@code claim_status_history} to {@code claim_status_transition}). Any other transition must be
 * rejected; {@code APPROVED -> CLOSED} is deliberately NOT allowed — the settlement step cannot be
 * skipped (decision A10).
 */
public enum ClaimStatus {
  DRAFT,
  SUBMITTED,
  UNDER_REVIEW,
  INTEGRATION_REQUESTED,
  APPRAISAL,
  APPROVED,
  REJECTED,
  SETTLEMENT,
  CLOSED,
  ARCHIVED;

  /**
   * States reachable from this one in a single transition.
   *
   * <p>Mirrored one-to-one by the seed of {@code coreapp.claim_status_transition}; the parity is
   * asserted by {@code ClaimStatusTransitionContractTest}.
   *
   * @return an immutable-by-convention set, never {@code null} (empty for terminal states)
   */
  public Set<ClaimStatus> allowedTransitions() {
    return switch (this) {
      case DRAFT -> EnumSet.of(SUBMITTED);
      case SUBMITTED -> EnumSet.of(UNDER_REVIEW);
      case UNDER_REVIEW -> EnumSet.of(INTEGRATION_REQUESTED, APPRAISAL, APPROVED, REJECTED);
      case INTEGRATION_REQUESTED -> EnumSet.of(UNDER_REVIEW);
      case APPRAISAL -> EnumSet.of(UNDER_REVIEW, APPROVED, REJECTED);
      case APPROVED -> EnumSet.of(SETTLEMENT);
      case SETTLEMENT -> EnumSet.of(CLOSED);
      case REJECTED -> EnumSet.of(CLOSED);
      case CLOSED -> EnumSet.of(ARCHIVED);
      case ARCHIVED -> EnumSet.noneOf(ClaimStatus.class);
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
   * @return {@code true} if no further transition is possible ({@code ARCHIVED} only)
   */
  public boolean isTerminal() {
    return this == ARCHIVED;
  }
}
