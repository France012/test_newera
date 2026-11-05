package it.enterprise.claims.core.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-access port over {@code coreapp.claim_assignments}.
 *
 * <p>No business rule here: the "at most one active holder per (claim, role)" invariant is enforced
 * by the partial unique index {@code uk_claim_assignments_active_role} in PostgreSQL. This
 * interface only exposes the finders the adapter needs, always distinguishing active rows ({@code
 * unassigned_at is null}) from the historical ones.
 */
public interface ClaimAssignmentJpaRepository
    extends JpaRepository<ClaimAssignmentJpaEntity, UUID> {

  /**
   * @return the active assignments of a claim, most recent first
   */
  List<ClaimAssignmentJpaEntity> findByClaimIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
      UUID claimId);

  /**
   * @return the full assignment history of a claim, most recent first
   */
  List<ClaimAssignmentJpaEntity> findByClaimIdOrderByAssignedAtDesc(UUID claimId);

  /**
   * @return the active holder of {@code roleCode} on a claim, if any
   */
  Optional<ClaimAssignmentJpaEntity> findByClaimIdAndRoleCodeAndUnassignedAtIsNull(
      UUID claimId, String roleCode);

  /**
   * @return the active assignments of an actor ("my claims"), most recent first
   */
  List<ClaimAssignmentJpaEntity> findByActorIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
      UUID actorId);
}
