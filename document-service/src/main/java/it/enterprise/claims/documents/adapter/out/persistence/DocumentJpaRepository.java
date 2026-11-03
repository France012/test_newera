package it.enterprise.claims.documents.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-access port over {@code documents.documents}.
 *
 * <p>All read methods exclude logically deleted rows by contract: the soft-delete filter is part of
 * the query derivation ({@code DeletedAtIsNull}) so it cannot be forgotten by callers.
 */
public interface DocumentJpaRepository extends JpaRepository<DocumentJpaEntity, UUID> {

  Optional<DocumentJpaEntity> findByIdAndDeletedAtIsNull(UUID id);

  Page<DocumentJpaEntity> findByClaimIdAndDeletedAtIsNull(UUID claimId, Pageable pageable);

  long countByClaimIdAndDeletedAtIsNull(UUID claimId);
}
