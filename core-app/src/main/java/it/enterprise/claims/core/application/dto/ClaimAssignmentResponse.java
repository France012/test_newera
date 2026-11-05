package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.ClaimAssignment;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API representation of a claim assignment (blueprint §7.1).
 *
 * @param id identifier of the assignment row
 * @param claimId claim the actor is assigned to
 * @param actorId assigned actor
 * @param actorDisplayName display name of that actor
 * @param roleCode role held on the claim
 * @param assignedAt assignment timestamp
 * @param assignedByActorId actor who performed the assignment, may be {@code null}
 * @param unassignedAt un-assignment timestamp, {@code null} while active
 */
public record ClaimAssignmentResponse(
    UUID id,
    UUID claimId,
    UUID actorId,
    String actorDisplayName,
    String roleCode,
    OffsetDateTime assignedAt,
    UUID assignedByActorId,
    OffsetDateTime unassignedAt) {

  /**
   * @param assignment domain assignment to expose
   * @return the API representation
   */
  public static ClaimAssignmentResponse from(ClaimAssignment assignment) {
    return new ClaimAssignmentResponse(
        assignment.id(),
        assignment.claimId(),
        assignment.actorId(),
        assignment.actorDisplayName(),
        assignment.roleCode(),
        assignment.assignedAt(),
        assignment.assignedByActorId(),
        assignment.unassignedAt());
  }
}
