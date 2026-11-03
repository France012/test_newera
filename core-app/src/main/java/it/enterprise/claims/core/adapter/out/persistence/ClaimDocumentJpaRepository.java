package it.enterprise.claims.core.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data-access port over the {@code coreapp.claim_documents} link table. */
public interface ClaimDocumentJpaRepository
    extends JpaRepository<ClaimDocumentJpaEntity, ClaimDocumentId> {

  List<ClaimDocumentJpaEntity> findByClaimId(UUID claimId);

  /**
   * Batch variant used by the claims list endpoint: one query for a whole page of claims instead of
   * one query per claim (avoids N+1). Written as explicit JPQL rather than query derivation because
   * the path goes through the {@code @EmbeddedId} of the link table.
   *
   * @param claimIds claims of the current page, must not be empty
   * @return every link row belonging to those claims
   */
  @Query("select link from ClaimDocumentJpaEntity link where link.id.claimId in :claimIds")
  List<ClaimDocumentJpaEntity> findByClaimIds(@Param("claimIds") Collection<UUID> claimIds);

  boolean existsByIdClaimIdAndIdDocumentId(UUID claimId, UUID documentId);

  long deleteByIdClaimIdAndIdDocumentId(UUID claimId, UUID documentId);
}
