package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Data-access port over {@code coreapp.claims}.
 *
 * <p>Query derivation only - no {@code @Query} and no business rules here. The translation to
 * domain objects is the responsibility of the repository adapter.
 */
public interface ClaimJpaRepository
    extends JpaRepository<ClaimJpaEntity, UUID>, JpaSpecificationExecutor<ClaimJpaEntity> {

  Optional<ClaimJpaEntity> findByClaimNumber(String claimNumber);

  boolean existsByClaimNumber(String claimNumber);

  Page<ClaimJpaEntity> findByStatus(ClaimStatus status, Pageable pageable);

  long countByStatus(ClaimStatus status);

  Page<ClaimJpaEntity> findByPolicyId(UUID policyId, Pageable pageable);
}
