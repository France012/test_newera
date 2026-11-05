package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.domain.model.Claim;
import it.enterprise.claims.core.domain.model.ClaimDocumentLink;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import it.enterprise.claims.core.domain.model.ClaimStatusHistory;
import it.enterprise.claims.core.domain.port.ClaimRepositoryPort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter implementing {@link ClaimRepositoryPort} on top of the JPA entities,
 * repositories and Flyway-managed schema owned by the data layer.
 *
 * <p>Responsibilities kept here (and only here):
 *
 * <ul>
 *   <li>entity {@code <->} domain translation, so {@code ClaimJpaEntity} never leaves the adapter;
 *   <li>hydration of the denormalised fields the API exposes ({@code policyNumber}, {@code
 *       customerId}, the author of a transition);
 *   <li>writing the history row of every status transition, in the same transaction as the status
 *       update, so the trail can never diverge from the state;
 *   <li>the closure/archival timestamps required by {@code
 *       ck_claims_closed_at_requires_closed_status} and {@code
 *       ck_claims_archived_at_requires_archived_status};
 *   <li>batch loading of policies, documents and actors for the read endpoints (no N+1).
 * </ul>
 */
@Repository
public class ClaimRepositoryAdapter implements ClaimRepositoryPort {

  private static final String OPENING_NOTE = "Claim opened";

  private final ClaimJpaRepository claimJpaRepository;
  private final ClaimStatusHistoryJpaRepository statusHistoryJpaRepository;
  private final ClaimDocumentJpaRepository claimDocumentJpaRepository;
  private final PolicyJpaRepository policyJpaRepository;
  private final ActorJpaRepository actorJpaRepository;

  /**
   * @param claimJpaRepository access to {@code coreapp.claims}
   * @param statusHistoryJpaRepository access to {@code coreapp.claim_status_history}
   * @param claimDocumentJpaRepository access to {@code coreapp.claim_documents}
   * @param policyJpaRepository access to {@code coreapp.policies} (read-only here)
   * @param actorJpaRepository access to {@code coreapp.actors} (read-only here, to name the author
   *     of a transition)
   */
  public ClaimRepositoryAdapter(
      ClaimJpaRepository claimJpaRepository,
      ClaimStatusHistoryJpaRepository statusHistoryJpaRepository,
      ClaimDocumentJpaRepository claimDocumentJpaRepository,
      PolicyJpaRepository policyJpaRepository,
      ActorJpaRepository actorJpaRepository) {
    this.claimJpaRepository = claimJpaRepository;
    this.statusHistoryJpaRepository = statusHistoryJpaRepository;
    this.claimDocumentJpaRepository = claimDocumentJpaRepository;
    this.policyJpaRepository = policyJpaRepository;
    this.actorJpaRepository = actorJpaRepository;
  }

  @Override
  @Transactional
  public Claim create(Claim claim) {
    ClaimJpaEntity entity =
        new ClaimJpaEntity(
            claim.id(),
            claim.claimNumber(),
            claim.policyId(),
            claim.claimType(),
            claim.description(),
            claim.incidentDate(),
            claim.status());
    entity.setOpenedAt(claim.openedAt());
    entity.setUpdatedAt(claim.updatedAt());
    ClaimJpaEntity saved = claimJpaRepository.saveAndFlush(entity);

    statusHistoryJpaRepository.save(
        new ClaimStatusHistoryJpaEntity(
            UUID.randomUUID(), saved.getId(), null, saved.getStatus(), OPENING_NOTE));

    return toDomain(saved, findPolicy(saved.getPolicyId()).orElse(null), List.of());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Claim> findById(UUID claimId) {
    return claimJpaRepository
        .findById(claimId)
        .map(
            entity ->
                toDomain(
                    entity,
                    findPolicy(entity.getPolicyId()).orElse(null),
                    activeDocumentIds(claimId)));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsByClaimNumber(String claimNumber) {
    return claimJpaRepository.existsByClaimNumber(claimNumber);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Claim> findPage(ClaimStatus status, Pageable pageable) {
    Page<ClaimJpaEntity> page =
        status == null
            ? claimJpaRepository.findAll(pageable)
            : claimJpaRepository.findByStatus(status, pageable);
    if (page.isEmpty()) {
      return page.map(entity -> toDomain(entity, null, List.of()));
    }
    Map<UUID, PolicyJpaEntity> policies = loadPolicies(page.getContent());
    Map<UUID, List<UUID>> documentIds = loadDocumentIds(page.getContent());
    return page.map(
        entity ->
            toDomain(
                entity,
                policies.get(entity.getPolicyId()),
                documentIds.getOrDefault(entity.getId(), List.of())));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ClaimStatusHistory> findStatusHistory(UUID claimId) {
    List<ClaimStatusHistoryJpaEntity> rows =
        statusHistoryJpaRepository.findByClaimIdOrderByChangedAtAsc(claimId);
    Map<UUID, String> actors = loadActorNames(rows);
    return rows.stream()
        .map(
            entity ->
                new ClaimStatusHistory(
                    entity.getId(),
                    entity.getClaimId(),
                    entity.getFromStatus(),
                    entity.getToStatus(),
                    entity.getNote(),
                    entity.getChangedAt(),
                    entity.getActorId(),
                    entity.getActorId() == null ? null : actors.get(entity.getActorId())))
        .toList();
  }

  @Override
  @Transactional
  public Claim updateStatus(Claim claim, ClaimStatus targetStatus, String note, UUID actorId) {
    ClaimJpaEntity entity =
        claimJpaRepository
            .findById(claim.id())
            .orElseThrow(() -> new ClaimNotFoundException(claim.id()));

    ClaimStatus previousStatus = entity.getStatus();
    OffsetDateTime now = OffsetDateTime.now();
    entity.setStatus(targetStatus);
    entity.setUpdatedAt(now);
    if (targetStatus == ClaimStatus.CLOSED && entity.getClosedAt() == null) {
      entity.setClosedAt(now);
    }
    if (targetStatus == ClaimStatus.ARCHIVED && entity.getArchivedAt() == null) {
      entity.setArchivedAt(now);
    }
    ClaimJpaEntity saved = claimJpaRepository.saveAndFlush(entity);

    statusHistoryJpaRepository.save(
        new ClaimStatusHistoryJpaEntity(
            UUID.randomUUID(), saved.getId(), previousStatus, targetStatus, note, actorId));

    return toDomain(
        saved, findPolicy(saved.getPolicyId()).orElse(null), activeDocumentIds(saved.getId()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ClaimDocumentLink> findDocumentLinks(UUID claimId) {
    return claimDocumentJpaRepository.findByClaimId(claimId).stream()
        .sorted(Comparator.comparing(ClaimDocumentJpaEntity::getLinkedAt))
        .map(ClaimRepositoryAdapter::toLink)
        .toList();
  }

  @Override
  @Transactional
  public ClaimDocumentLink unlinkDocument(UUID claimId, UUID documentId) {
    ClaimDocumentJpaEntity link =
        claimDocumentJpaRepository
            .findById(new ClaimDocumentId(claimId, documentId))
            .orElseThrow(() -> new ClaimNotFoundException(claimId));
    if (link.isActive()) {
      link.unlink(OffsetDateTime.now());
    }
    return toLink(claimDocumentJpaRepository.saveAndFlush(link));
  }

  private List<UUID> activeDocumentIds(UUID claimId) {
    return claimDocumentJpaRepository.findByClaimId(claimId).stream()
        .filter(ClaimDocumentJpaEntity::isActive)
        .map(link -> link.getId().getDocumentId())
        .toList();
  }

  private Map<UUID, String> loadActorNames(Collection<ClaimStatusHistoryJpaEntity> rows) {
    List<UUID> actorIds =
        rows.stream()
            .map(ClaimStatusHistoryJpaEntity::getActorId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<UUID, String> names = new LinkedHashMap<>();
    if (actorIds.isEmpty()) {
      return names;
    }
    actorJpaRepository
        .findAllById(actorIds)
        .forEach(actor -> names.put(actor.getId(), actor.getDisplayName()));
    return names;
  }

  private Optional<PolicyJpaEntity> findPolicy(UUID policyId) {
    return policyId == null ? Optional.empty() : policyJpaRepository.findById(policyId);
  }

  private Map<UUID, PolicyJpaEntity> loadPolicies(Collection<ClaimJpaEntity> claims) {
    List<UUID> policyIds = claims.stream().map(ClaimJpaEntity::getPolicyId).distinct().toList();
    Map<UUID, PolicyJpaEntity> policies = new HashMap<>();
    policyJpaRepository
        .findAllById(policyIds)
        .forEach(policy -> policies.put(policy.getId(), policy));
    return policies;
  }

  private Map<UUID, List<UUID>> loadDocumentIds(Collection<ClaimJpaEntity> claims) {
    List<UUID> claimIds = claims.stream().map(ClaimJpaEntity::getId).distinct().toList();
    Map<UUID, List<UUID>> byClaim = new HashMap<>();
    for (ClaimDocumentJpaEntity link : claimDocumentJpaRepository.findByClaimIds(claimIds)) {
      if (!link.isActive()) {
        continue;
      }
      byClaim
          .computeIfAbsent(link.getId().getClaimId(), key -> new ArrayList<>())
          .add(link.getId().getDocumentId());
    }
    return byClaim;
  }

  private static ClaimDocumentLink toLink(ClaimDocumentJpaEntity entity) {
    return new ClaimDocumentLink(
        entity.getId().getClaimId(),
        entity.getId().getDocumentId(),
        entity.getLinkedAt(),
        entity.getLinkedByActorId(),
        entity.getUnlinkedAt());
  }

  private static Claim toDomain(
      ClaimJpaEntity entity, PolicyJpaEntity policy, List<UUID> documentIds) {
    return new Claim(
        entity.getId(),
        entity.getClaimNumber(),
        entity.getPolicyId(),
        policy == null ? null : policy.getPolicyNumber(),
        policy == null ? null : policy.getCustomerId(),
        entity.getClaimType(),
        entity.getStatus(),
        entity.getDescription(),
        entity.getIncidentDate(),
        entity.getOpenedAt(),
        entity.getUpdatedAt(),
        documentIds,
        entity.getClosedAt(),
        entity.getArchivedAt());
  }
}
