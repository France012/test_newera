package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.domain.model.Claim;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import it.enterprise.claims.core.domain.model.ClaimStatusHistory;
import it.enterprise.claims.core.domain.port.ClaimRepositoryPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *       customerId}) from the referenced policy;
 *   <li>writing the audit row of every status transition, in the same transaction as the status
 *       update, so the trail can never diverge from the state;
 *   <li>batch loading of policies and document ids for list endpoints (no N+1).
 * </ul>
 */
@Repository
public class ClaimRepositoryAdapter implements ClaimRepositoryPort {

  private static final String OPENING_NOTE = "Claim opened";

  private final ClaimJpaRepository claimJpaRepository;
  private final ClaimStatusHistoryJpaRepository statusHistoryJpaRepository;
  private final ClaimDocumentJpaRepository claimDocumentJpaRepository;
  private final PolicyJpaRepository policyJpaRepository;

  /**
   * @param claimJpaRepository access to {@code coreapp.claims}
   * @param statusHistoryJpaRepository access to {@code coreapp.claim_status_history}
   * @param claimDocumentJpaRepository access to {@code coreapp.claim_documents}
   * @param policyJpaRepository access to {@code coreapp.policies} (read-only here)
   */
  public ClaimRepositoryAdapter(
      ClaimJpaRepository claimJpaRepository,
      ClaimStatusHistoryJpaRepository statusHistoryJpaRepository,
      ClaimDocumentJpaRepository claimDocumentJpaRepository,
      PolicyJpaRepository policyJpaRepository) {
    this.claimJpaRepository = claimJpaRepository;
    this.statusHistoryJpaRepository = statusHistoryJpaRepository;
    this.claimDocumentJpaRepository = claimDocumentJpaRepository;
    this.policyJpaRepository = policyJpaRepository;
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
                    claimDocumentJpaRepository.findByClaimId(claimId).stream()
                        .map(link -> link.getId().getDocumentId())
                        .toList()));
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
    return statusHistoryJpaRepository.findByClaimIdOrderByChangedAtAsc(claimId).stream()
        .map(
            entity ->
                new ClaimStatusHistory(
                    entity.getId(),
                    entity.getClaimId(),
                    entity.getFromStatus(),
                    entity.getToStatus(),
                    entity.getNote(),
                    entity.getChangedAt()))
        .toList();
  }

  @Override
  @Transactional
  public Claim updateStatus(Claim claim, ClaimStatus targetStatus, String note) {
    ClaimJpaEntity entity =
        claimJpaRepository
            .findById(claim.id())
            .orElseThrow(() -> new ClaimNotFoundException(claim.id()));

    ClaimStatus previousStatus = entity.getStatus();
    entity.setStatus(targetStatus);
    ClaimJpaEntity saved = claimJpaRepository.saveAndFlush(entity);

    statusHistoryJpaRepository.save(
        new ClaimStatusHistoryJpaEntity(
            UUID.randomUUID(), saved.getId(), previousStatus, targetStatus, note));

    return toDomain(
        saved,
        findPolicy(saved.getPolicyId()).orElse(null),
        claimDocumentJpaRepository.findByClaimId(saved.getId()).stream()
            .map(link -> link.getId().getDocumentId())
            .toList());
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
      byClaim
          .computeIfAbsent(link.getId().getClaimId(), key -> new ArrayList<>())
          .add(link.getId().getDocumentId());
    }
    return byClaim;
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
        documentIds);
  }
}
