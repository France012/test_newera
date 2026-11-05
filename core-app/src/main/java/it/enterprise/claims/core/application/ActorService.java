package it.enterprise.claims.core.application;

import it.enterprise.claims.core.application.dto.ActorCreateRequest;
import it.enterprise.claims.core.application.dto.ActorPageResponse;
import it.enterprise.claims.core.application.dto.ActorResponse;
import it.enterprise.claims.core.application.dto.ActorRolesUpdateRequest;
import it.enterprise.claims.core.application.dto.ActorUpdateRequest;
import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.DuplicateActorException;
import it.enterprise.claims.core.application.exception.UnknownRoleException;
import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.ActorType;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.port.ActorRepositoryPort;
import it.enterprise.claims.core.domain.port.RoleRepositoryPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the actors and of their role grants ({@code coreapp.actors}, {@code
 * coreapp.actor_roles}).
 *
 * <p>Business rules enforced here, not in the controller and not in the entity:
 *
 * <ul>
 *   <li>a {@code subjectRef} is registered once for its whole lifetime, soft-deleted rows included,
 *       so a duplicate is a 409;
 *   <li>a role code outside the seeded reference data is a 400, never a free-text field;
 *   <li>every creation and every change of the role set is appended to the audit trail in the same
 *       transaction.
 * </ul>
 */
@Service
public class ActorService {

  private static final String ENTITY_TYPE = "ACTOR";

  private final ActorRepositoryPort actorRepository;
  private final RoleRepositoryPort roleRepository;
  private final AuditRecorder auditRecorder;
  private final Clock clock;

  /**
   * @param actorRepository actor persistence port
   * @param roleRepository role reference data port
   * @param auditRecorder audit trail writer
   * @param clock clock used to timestamp the new actor (injectable in tests)
   */
  public ActorService(
      ActorRepositoryPort actorRepository,
      RoleRepositoryPort roleRepository,
      AuditRecorder auditRecorder,
      Clock clock) {
    this.actorRepository = actorRepository;
    this.roleRepository = roleRepository;
    this.auditRecorder = auditRecorder;
    this.clock = clock;
  }

  /**
   * Registers a new actor with an initial set of roles.
   *
   * @param request validated payload
   * @return the created actor
   * @throws DuplicateActorException when the {@code subjectRef} is already registered (HTTP 409)
   * @throws UnknownRoleException when a role code is not part of the reference data (HTTP 400)
   */
  @Transactional
  public ActorResponse create(ActorCreateRequest request) {
    if (actorRepository.existsBySubjectRef(request.subjectRef())) {
      throw new DuplicateActorException(request.subjectRef());
    }
    Set<String> roleCodes = normalizedRoles(request.roleCodes());

    OffsetDateTime now = OffsetDateTime.now(clock);
    Actor actor =
        new Actor(
            UUID.randomUUID(),
            request.subjectRef(),
            request.displayName(),
            request.email(),
            request.actorType(),
            true,
            roleCodes,
            now,
            now);

    Actor created = actorRepository.create(actor);
    auditRecorder.record(
        AuditEventType.ACTOR_CREATED,
        ENTITY_TYPE,
        created.id(),
        null,
        created.id(),
        Map.of("subjectRef", created.subjectRef(), "roleCodes", created.roleCodes()));
    return ActorResponse.from(created);
  }

  /**
   * Updates the mutable attributes of an actor; {@code null} fields are left untouched.
   *
   * @param actorId actor to update
   * @param request patch payload
   * @return the updated actor
   * @throws ActorNotFoundException when the actor does not exist or is logically deleted
   */
  @Transactional
  public ActorResponse update(UUID actorId, ActorUpdateRequest request) {
    Actor actor = load(actorId);
    Actor updated =
        new Actor(
            actor.id(),
            actor.subjectRef(),
            request.displayName() == null ? actor.displayName() : request.displayName(),
            request.email() == null ? actor.email() : request.email(),
            actor.type(),
            request.enabled() == null ? actor.enabled() : request.enabled(),
            actor.roleCodes(),
            actor.createdAt(),
            OffsetDateTime.now(clock));
    return ActorResponse.from(actorRepository.update(updated));
  }

  /**
   * Replaces the role set of an actor (idempotent full replacement).
   *
   * @param actorId actor whose roles are replaced
   * @param request complete set of role codes the actor must hold
   * @return the actor with the effective role set
   * @throws ActorNotFoundException when the actor does not exist
   * @throws UnknownRoleException when a role code is not part of the reference data
   */
  @Transactional
  public ActorResponse replaceRoles(UUID actorId, ActorRolesUpdateRequest request) {
    Actor actor = load(actorId);
    Set<String> roleCodes = normalizedRoles(request.roleCodes());
    Set<String> effective = actorRepository.replaceRoles(actorId, roleCodes, null);
    auditRecorder.record(
        AuditEventType.ACTOR_ROLES_CHANGED,
        ENTITY_TYPE,
        actorId,
        null,
        null,
        Map.of("before", actor.roleCodes(), "after", effective));
    return ActorResponse.from(actor.withRoleCodes(effective));
  }

  /**
   * @param actorId identifier to read
   * @return the actor
   * @throws ActorNotFoundException when the actor does not exist
   */
  @Transactional(readOnly = true)
  public ActorResponse findById(UUID actorId) {
    return ActorResponse.from(load(actorId));
  }

  /**
   * @param roleCode optional filter on an actively granted role
   * @param actorType optional filter on the actor type
   * @param enabled optional filter on the enabled flag
   * @param pageable page request
   * @return one page of live actors
   */
  @Transactional(readOnly = true)
  public ActorPageResponse findAll(
      String roleCode, ActorType actorType, Boolean enabled, Pageable pageable) {
    return ActorPageResponse.from(actorRepository.findPage(roleCode, actorType, enabled, pageable));
  }

  private Actor load(UUID actorId) {
    return actorRepository.findById(actorId).orElseThrow(() -> new ActorNotFoundException(actorId));
  }

  private Set<String> normalizedRoles(Set<String> roleCodes) {
    Set<String> normalized = new LinkedHashSet<>();
    if (roleCodes == null) {
      return normalized;
    }
    for (String roleCode : roleCodes) {
      String candidate = roleCode == null ? "" : roleCode.trim();
      if (!roleRepository.existsByCode(candidate)) {
        throw new UnknownRoleException(candidate);
      }
      normalized.add(candidate);
    }
    return normalized;
  }
}
