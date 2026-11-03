package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.model.Policy;
import it.enterprise.claims.core.domain.port.PolicyRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence adapter implementing {@link PolicyRepositoryPort} over {@code coreapp.policies}. */
@Repository
public class PolicyRepositoryAdapter implements PolicyRepositoryPort {

  private final PolicyJpaRepository policyJpaRepository;

  /**
   * @param policyJpaRepository access to {@code coreapp.policies}
   */
  public PolicyRepositoryAdapter(PolicyJpaRepository policyJpaRepository) {
    this.policyJpaRepository = policyJpaRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Policy> findByPolicyNumber(String policyNumber) {
    return policyJpaRepository
        .findByPolicyNumber(policyNumber)
        .map(PolicyRepositoryAdapter::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Policy> findById(UUID policyId) {
    return policyJpaRepository.findById(policyId).map(PolicyRepositoryAdapter::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Policy> findPage(Pageable pageable) {
    return policyJpaRepository.findAll(pageable).map(PolicyRepositoryAdapter::toDomain);
  }

  private static Policy toDomain(PolicyJpaEntity entity) {
    return new Policy(
        entity.getId(),
        entity.getPolicyNumber(),
        entity.getCustomerId(),
        entity.getProductCode(),
        entity.getValidFrom(),
        entity.getValidTo(),
        entity.getPremium());
  }
}
