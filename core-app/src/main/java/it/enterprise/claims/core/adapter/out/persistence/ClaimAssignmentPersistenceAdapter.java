package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.model.ClaimAssignment;
import it.enterprise.claims.core.domain.port.ClaimAssignmentPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter implementing {@link ClaimAssignmentPort} over {@code
 * coreapp.claim_assignments}.
 *
 * <p>The invariant "at most one active holder per {@code (claim, role)}" is respected by {@link
 * #assign(ClaimAssignment)}, which closes the previous row <em>before</em> inserting the new one;
 * the partial unique index {@code uk_claim_assignments_active_role} stays as the last line of
 * defence against two concurrent callers.
 */
@Repository
public class ClaimAssignmentPersistenceAdapter implements ClaimAssignmentPort {

  private final ClaimAssignmentJpaRepository claimAssignmentJpaRepository;
  private final ActorJpaRepository actorJpaRepository;

  /**
   * @param claimAssignmentJpaRepository access to {@code coreapp.claim_assignments}
   * @param actorJpaRepository read access to {@code coreapp.actors}, used to resolve the display
   *     name exposed by the API
   */
  public ClaimAssignmentPersistenceAdapter(
      ClaimAssignmentJpaRepository claimAssignmentJpaRepository,
      ActorJpaRepository actorJpaRepository) {
    this.claimAssignmentJpaRepository = claimAssignmentJpaRepository;
    this.actorJpaRepository = actorJpaRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ClaimAssignment> findByClaimId(UUID claimId, boolean activeOnly) {
    List<ClaimAssignmentJpaEntity> rows =
        activeOnly
            ? claimAssignmentJpaRepository.findByClaimIdAndUnassignedAtIsNullOrderByAssignedAtDesc(
                claimId)
            : claimAssignmentJpaRepository.findByClaimIdOrderByAssignedAtDesc(claimId);
    return rows.stream().map(row -> toDomain(row, displayNames(rows))).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ClaimAssignment> findActive(UUID claimId, String roleCode) {
    return claimAssignmentJpaRepository
        .findByClaimIdAndRoleCodeAndUnassignedAtIsNull(claimId, roleCode)
        .map(row -> toDomain(row, displayNames(List.of(row))));
  }

  @Override
  @Transactional
  public ClaimAssignment assign(ClaimAssignment assignment) {
    claimAssignmentJpaRepository
        .findByClaimIdAndRoleCodeAndUnassignedAtIsNull(assignment.claimId(), assignment.roleCode())
        .ifPresent(
            previous -> {
              previous.unassign(assignment.assignedAt());
              claimAssignmentJpaRepository.save(previous);
              claimAssignmentJpaRepository.flush();
            });

    ClaimAssignmentJpaEntity entity =
        new ClaimAssignmentJpaEntity(
            assignment.id(),
            assignment.claimId(),
            assignment.actorId(),
            assignment.roleCode(),
            assignment.assignedByActorId());
    entity.setAssignedAt(assignment.assignedAt());
    ClaimAssignmentJpaEntity saved = claimAssignmentJpaRepository.saveAndFlush(entity);
    return toDomain(saved, displayNames(List.of(saved)));
  }

  @Override
  @Transactional
  public Optional<ClaimAssignment> unassign(UUID claimId, String roleCode) {
    return claimAssignmentJpaRepository
        .findByClaimIdAndRoleCodeAndUnassignedAtIsNull(claimId, roleCode)
        .map(
            row -> {
              row.unassign(null);
              ClaimAssignmentJpaEntity saved = claimAssignmentJpaRepository.saveAndFlush(row);
              return toDomain(saved, displayNames(List.of(saved)));
            });
  }

  private Map<UUID, String> displayNames(List<ClaimAssignmentJpaEntity> rows) {
    List<UUID> actorIds =
        rows.stream().map(ClaimAssignmentJpaEntity::getActorId).distinct().toList();
    Map<UUID, String> names = new LinkedHashMap<>();
    actorJpaRepository
        .findAllById(actorIds)
        .forEach(actor -> names.put(actor.getId(), actor.getDisplayName()));
    return names;
  }

  private static ClaimAssignment toDomain(
      ClaimAssignmentJpaEntity entity, Map<UUID, String> displayNames) {
    return new ClaimAssignment(
        entity.getId(),
        entity.getClaimId(),
        entity.getActorId(),
        displayNames.get(entity.getActorId()),
        entity.getRoleCode(),
        entity.getAssignedAt(),
        entity.getAssignedBy(),
        entity.getUnassignedAt());
  }
}
