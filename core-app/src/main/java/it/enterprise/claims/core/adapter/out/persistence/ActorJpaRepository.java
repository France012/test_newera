package it.enterprise.claims.core.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data-access port over {@code coreapp.actors}.
 *
 * <p>Decision DD-6 (soft delete): every read method <strong>embeds</strong> {@code
 * DeletedAtIsNull}, so the filter cannot be forgotten by a caller. The only method that may return
 * a deleted row is {@link #findBySubjectRef(String)} — needed to reactivate an identity instead of
 * re-creating it (the unique key covers deleted rows too).
 */
public interface ActorJpaRepository extends JpaRepository<ActorJpaEntity, UUID> {

  /**
   * @return the actor only if it exists and is not logically deleted
   */
  Optional<ActorJpaEntity> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * Lookup by external IdP subject, deleted rows included (unique key spans deleted rows too).
   *
   * @param subjectRef external IdP subject
   * @return the actor, whatever its soft-delete state
   */
  Optional<ActorJpaEntity> findBySubjectRef(String subjectRef);

  boolean existsBySubjectRef(String subjectRef);

  /**
   * @return live (non-deleted) actors, paged
   */
  Page<ActorJpaEntity> findByDeletedAtIsNull(Pageable pageable);

  /**
   * @return live actors of one type
   */
  Page<ActorJpaEntity> findByActorTypeAndDeletedAtIsNull(String actorType, Pageable pageable);

  /**
   * @return live and enabled actors (the only ones that can be assigned to a claim)
   */
  List<ActorJpaEntity> findByEnabledTrueAndDeletedAtIsNull();

  /**
   * Single query behind {@code GET /api/v1/actors}: every filter is optional, the soft-delete
   * filter is not.
   *
   * @param roleCode optional code of a role the actor must currently hold, may be {@code null}
   * @param actorType optional actor type, may be {@code null}
   * @param enabled optional enabled flag, may be {@code null}
   * @param pageable page request
   * @return the matching live actors
   */
  @Query(
      "select a from ActorJpaEntity a"
          + " where a.deletedAt is null"
          + " and (:actorType is null or a.actorType = :actorType)"
          + " and (:enabled is null or a.enabled = :enabled)"
          + " and (:roleCode is null or exists ("
          + "  select r.id.roleCode from ActorRoleJpaEntity r"
          + "  where r.id.actorId = a.id and r.id.roleCode = :roleCode and r.revokedAt is null))")
  Page<ActorJpaEntity> search(
      @Param("roleCode") String roleCode,
      @Param("actorType") String actorType,
      @Param("enabled") Boolean enabled,
      Pageable pageable);
}
