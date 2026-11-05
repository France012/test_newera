package it.enterprise.claims.core.domain.port;

import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.ActorType;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Outbound port for {@link Actor} persistence.
 *
 * <p>Deletion is logical (decision DD-6): every read of the adapter filters {@code deleted_at is
 * null} on its own, so a caller cannot forget the filter.
 */
public interface ActorRepositoryPort {

  /**
   * Inserts a new actor together with the given role grants.
   *
   * @param actor actor to insert
   * @return the persisted actor, roles included
   */
  Actor create(Actor actor);

  /**
   * Updates the mutable attributes of an actor (name, email, enabled flag). Roles are changed
   * through {@link #replaceRoles(UUID, Set, UUID)}.
   *
   * @param actor actor with the new values
   * @return the persisted actor
   */
  Actor update(Actor actor);

  /**
   * @param actorId identifier to look up
   * @return the actor unless it does not exist or is logically deleted
   */
  Optional<Actor> findById(UUID actorId);

  /**
   * @param subjectRef external IdP subject
   * @return {@code true} when the subject is already registered, soft-deleted rows included
   */
  boolean existsBySubjectRef(String subjectRef);

  /**
   * @param roleCode optional filter on an actively granted role, may be {@code null}
   * @param actorType optional filter on the nature of the actor, may be {@code null}
   * @param enabled optional filter on the enabled flag, may be {@code null}
   * @param pageable page request
   * @return the matching live actors
   */
  Page<Actor> findPage(String roleCode, ActorType actorType, Boolean enabled, Pageable pageable);

  /**
   * Replaces the role grants of an actor: revoked grants are revived, missing ones are inserted,
   * the ones no longer requested are revoked (never deleted).
   *
   * @param actorId actor whose roles are replaced
   * @param roleCodes complete set of roles the actor must hold
   * @param grantedBy actor performing the change, may be {@code null}
   * @return the effective set of active role codes after the call
   */
  Set<String> replaceRoles(UUID actorId, Set<String> roleCodes, UUID grantedBy);
}
