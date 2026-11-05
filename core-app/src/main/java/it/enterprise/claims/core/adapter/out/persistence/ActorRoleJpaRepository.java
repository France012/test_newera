package it.enterprise.claims.core.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-access port over {@code coreapp.actor_roles}.
 *
 * <p>Grant/revoke model: a revoked grant keeps its row and gets {@code revoked_at}, so every read
 * that asks "which roles does this actor hold now?" must filter it out - the query derivation makes
 * that filter part of the method name.
 */
public interface ActorRoleJpaRepository extends JpaRepository<ActorRoleJpaEntity, ActorRoleId> {

  /**
   * @param actorId actor whose grants are requested, revoked ones included
   * @return every grant row of the actor, ordered by role code
   */
  List<ActorRoleJpaEntity> findByIdActorIdOrderByIdRoleCodeAsc(UUID actorId);

  /**
   * @param actorId actor whose grants are requested
   * @return only the grants that are still active, ordered by role code
   */
  List<ActorRoleJpaEntity> findByIdActorIdAndRevokedAtIsNullOrderByIdRoleCodeAsc(UUID actorId);

  /**
   * Batch variant used by the actors list endpoint (avoids one query per actor).
   *
   * @param actorIds actors of the current page
   * @return the active grants of those actors
   */
  List<ActorRoleJpaEntity> findByIdActorIdInAndRevokedAtIsNull(Collection<UUID> actorIds);

  /**
   * @param actorId actor id
   * @param roleCode role code
   * @return the grant row, active or revoked
   */
  Optional<ActorRoleJpaEntity> findByIdActorIdAndIdRoleCode(UUID actorId, String roleCode);
}
