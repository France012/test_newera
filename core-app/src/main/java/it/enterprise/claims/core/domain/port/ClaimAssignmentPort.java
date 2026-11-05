package it.enterprise.claims.core.domain.port;

import it.enterprise.claims.core.domain.model.ClaimAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port over {@code coreapp.claim_assignments}.
 *
 * <p>Contract: at most one active holder per {@code (claim, role)}. The adapter closes the previous
 * row before inserting the new one; the partial unique index {@code
 * uk_claim_assignments_active_role} is the last line of defence if two callers race.
 */
public interface ClaimAssignmentPort {

  /**
   * @param claimId claim whose assignments are requested
   * @param activeOnly {@code true} to return only the current holders
   * @return the assignments, most recent first
   */
  List<ClaimAssignment> findByClaimId(UUID claimId, boolean activeOnly);

  /**
   * @param claimId claim id
   * @param roleCode role code
   * @return the current holder of that role on the claim, if any
   */
  Optional<ClaimAssignment> findActive(UUID claimId, String roleCode);

  /**
   * Inserts a new active assignment, closing the previous holder of the same role.
   *
   * @param assignment assignment to insert
   * @return the persisted assignment
   */
  ClaimAssignment assign(ClaimAssignment assignment);

  /**
   * Closes the active assignment of a role without deleting the row.
   *
   * @param claimId claim id
   * @param roleCode role code
   * @return the closed assignment, or empty when there was no active holder
   */
  Optional<ClaimAssignment> unassign(UUID claimId, String roleCode);
}
