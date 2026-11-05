package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.ActorType;
import it.enterprise.claims.core.domain.port.ActorRepositoryPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter implementing {@link ActorRepositoryPort} over {@code coreapp.actors} and
 * {@code coreapp.actor_roles}.
 *
 * <p>Responsibilities kept here (and only here):
 *
 * <ul>
 *   <li>entity {@code <->} domain translation, roles included (the actor row does not store them);
 *   <li>the grant/revoke protocol of {@code actor_roles}: a role that is removed is revoked, never
 *       deleted, so the history survives and a re-grant is a revival of the same row;
 *   <li>soft-delete filtering on every read (DD-6) and batch loading of roles for list endpoints.
 * </ul>
 */
@Repository
public class ActorRepositoryAdapter implements ActorRepositoryPort {

  private final ActorJpaRepository actorJpaRepository;
  private final ActorRoleJpaRepository actorRoleJpaRepository;

  /**
   * @param actorJpaRepository access to {@code coreapp.actors}
   * @param actorRoleJpaRepository access to {@code coreapp.actor_roles}
   */
  public ActorRepositoryAdapter(
      ActorJpaRepository actorJpaRepository, ActorRoleJpaRepository actorRoleJpaRepository) {
    this.actorJpaRepository = actorJpaRepository;
    this.actorRoleJpaRepository = actorRoleJpaRepository;
  }

  @Override
  @Transactional
  public Actor create(Actor actor) {
    ActorJpaEntity entity =
        new ActorJpaEntity(
            actor.id(),
            actor.subjectRef(),
            actor.displayName(),
            actor.email(),
            actor.type().name());
    entity.setEnabled(actor.enabled());
    entity.setCreatedAt(actor.createdAt());
    entity.setUpdatedAt(actor.updatedAt());
    ActorJpaEntity saved = actorJpaRepository.saveAndFlush(entity);
    return toDomain(saved, grantCodes(grantAll(saved.getId(), actor.roleCodes(), null)));
  }

  @Override
  @Transactional
  public Actor update(Actor actor) {
    ActorJpaEntity entity =
        actorJpaRepository
            .findByIdAndDeletedAtIsNull(actor.id())
            .orElseThrow(() -> new ActorNotFoundException(actor.id()));
    entity.setDisplayName(actor.displayName());
    entity.setEmail(actor.email());
    entity.setEnabled(actor.enabled());
    ActorJpaEntity saved = actorJpaRepository.saveAndFlush(entity);
    return toDomain(saved, activeRoleCodes(saved.getId()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Actor> findById(UUID actorId) {
    return actorJpaRepository
        .findByIdAndDeletedAtIsNull(actorId)
        .map(entity -> toDomain(entity, activeRoleCodes(actorId)));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsBySubjectRef(String subjectRef) {
    return actorJpaRepository.existsBySubjectRef(subjectRef);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Actor> findPage(
      String roleCode, ActorType actorType, Boolean enabled, Pageable pageable) {
    Page<ActorJpaEntity> page =
        actorJpaRepository.search(
            roleCode, actorType == null ? null : actorType.name(), enabled, pageable);
    if (page.isEmpty()) {
      return page.map(entity -> toDomain(entity, Set.of()));
    }
    Map<UUID, Set<String>> rolesByActor = loadRoleCodes(page.getContent());
    return page.map(
        entity -> toDomain(entity, rolesByActor.getOrDefault(entity.getId(), Set.of())));
  }

  @Override
  @Transactional
  public Set<String> replaceRoles(UUID actorId, Set<String> roleCodes, UUID grantedBy) {
    actorJpaRepository
        .findByIdAndDeletedAtIsNull(actorId)
        .orElseThrow(() -> new ActorNotFoundException(actorId));

    Map<String, ActorRoleJpaEntity> existing = new HashMap<>();
    for (ActorRoleJpaEntity grant :
        actorRoleJpaRepository.findByIdActorIdOrderByIdRoleCodeAsc(actorId)) {
      existing.put(grant.getId().getRoleCode(), grant);
    }

    for (String roleCode : roleCodes) {
      ActorRoleJpaEntity grant = existing.get(roleCode);
      if (grant == null) {
        grantAll(actorId, Set.of(roleCode), grantedBy);
      } else if (!grant.isActive()) {
        grant.regrant();
        actorRoleJpaRepository.save(grant);
      }
    }
    for (ActorRoleJpaEntity grant : existing.values()) {
      if (grant.isActive() && !roleCodes.contains(grant.getId().getRoleCode())) {
        grant.revoke(null);
        actorRoleJpaRepository.save(grant);
      }
    }
    actorRoleJpaRepository.flush();
    return activeRoleCodes(actorId);
  }

  private List<ActorRoleJpaEntity> grantAll(
      UUID actorId, Collection<String> roleCodes, UUID grantedBy) {
    List<ActorRoleJpaEntity> grants = new ArrayList<>();
    for (String roleCode : new LinkedHashSet<>(roleCodes)) {
      grants.add(actorRoleJpaRepository.save(new ActorRoleJpaEntity(actorId, roleCode, grantedBy)));
    }
    return grants;
  }

  private Set<String> activeRoleCodes(UUID actorId) {
    Set<String> codes = new LinkedHashSet<>();
    actorRoleJpaRepository
        .findByIdActorIdAndRevokedAtIsNullOrderByIdRoleCodeAsc(actorId)
        .forEach(grant -> codes.add(grant.getId().getRoleCode()));
    return codes;
  }

  private Map<UUID, Set<String>> loadRoleCodes(Collection<ActorJpaEntity> actors) {
    List<UUID> actorIds = actors.stream().map(ActorJpaEntity::getId).distinct().toList();
    Map<UUID, Set<String>> byActor = new HashMap<>();
    for (ActorRoleJpaEntity grant :
        actorRoleJpaRepository.findByIdActorIdInAndRevokedAtIsNull(actorIds)) {
      byActor
          .computeIfAbsent(grant.getId().getActorId(), key -> new LinkedHashSet<>())
          .add(grant.getId().getRoleCode());
    }
    return byActor;
  }

  private static Actor toDomain(ActorJpaEntity entity, Set<String> roleCodes) {
    return new Actor(
        entity.getId(),
        entity.getSubjectRef(),
        entity.getDisplayName(),
        entity.getEmail(),
        ActorType.valueOf(entity.getActorType()),
        entity.isEnabled(),
        roleCodes,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static Set<String> grantCodes(List<ActorRoleJpaEntity> grants) {
    Set<String> codes = new LinkedHashSet<>();
    grants.forEach(grant -> codes.add(grant.getId().getRoleCode()));
    return codes;
  }
}
