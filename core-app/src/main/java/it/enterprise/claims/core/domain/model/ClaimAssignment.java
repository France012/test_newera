package it.enterprise.claims.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Assignment of an actor, in a given role, to a claim ({@code coreapp.claim_assignments}; Italian:
 * <em>assegnazione attore+ruolo su una pratica</em>).
 *
 * <p>At most one <em>active</em> holder per {@code (claimId, roleCode)}: the invariant is enforced
 * by the partial unique index {@code uk_claim_assignments_active_role}, so a re-assignment closes
 * the previous row ({@code unassignedAt}) instead of overwriting it, and the history stays
 * auditable.
 *
 * <p>{@code actorDisplayName} is a denormalised value resolved by the persistence adapter, because
 * the API exposes it next to the identifier.
 *
 * @param id identifier generated in the application layer
 * @param claimId claim the actor is assigned to
 * @param actorId assigned actor
 * @param actorDisplayName display name of that actor (resolved, not stored on the row)
 * @param roleCode role the actor holds on the claim
 * @param assignedAt assignment timestamp
 * @param assignedByActorId actor who performed the assignment, may be {@code null}
 * @param unassignedAt un-assignment timestamp, {@code null} while the assignment is active
 */
public record ClaimAssignment(
    UUID id,
    UUID claimId,
    UUID actorId,
    String actorDisplayName,
    String roleCode,
    OffsetDateTime assignedAt,
    UUID assignedByActorId,
    OffsetDateTime unassignedAt) {

  /** Compact constructor: enforces the invariants the database also enforces. */
  public ClaimAssignment {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(claimId, "claimId is required");
    Objects.requireNonNull(actorId, "actorId is required");
    Objects.requireNonNull(roleCode, "roleCode is required");
    Objects.requireNonNull(assignedAt, "assignedAt is required");
  }

  /**
   * @return {@code true} while the actor is the current holder of the role on the claim
   */
  public boolean isActive() {
    return unassignedAt == null;
  }
}
