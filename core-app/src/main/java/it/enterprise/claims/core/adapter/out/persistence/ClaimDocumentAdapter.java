package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.port.ClaimDocumentPort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter over the {@code coreapp.claim_documents} link table.
 *
 * <p>Linking is idempotent: the natural composite key {@code (claim_id, document_id)} prevents
 * duplicates, and the adapter checks for an existing row instead of relying on a constraint
 * violation, so replaying {@code POST /api/v1/claims/{id}/documents} is always safe.
 */
@Repository
public class ClaimDocumentAdapter implements ClaimDocumentPort {

  private final ClaimDocumentJpaRepository claimDocumentJpaRepository;

  /**
   * @param claimDocumentJpaRepository access to {@code coreapp.claim_documents}
   */
  public ClaimDocumentAdapter(ClaimDocumentJpaRepository claimDocumentJpaRepository) {
    this.claimDocumentJpaRepository = claimDocumentJpaRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findDocumentIds(UUID claimId) {
    return claimDocumentJpaRepository.findByClaimId(claimId).stream()
        .map(link -> link.getId().getDocumentId())
        .toList();
  }

  @Override
  @Transactional
  public List<UUID> link(UUID claimId, List<UUID> documentIds) {
    Set<UUID> all = new LinkedHashSet<>(findDocumentIds(claimId));
    for (UUID documentId : documentIds) {
      if (!claimDocumentJpaRepository.existsByIdClaimIdAndIdDocumentId(claimId, documentId)) {
        claimDocumentJpaRepository.save(new ClaimDocumentJpaEntity(claimId, documentId));
        all.add(documentId);
      }
    }
    return List.copyOf(all);
  }
}
