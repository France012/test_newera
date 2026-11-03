package it.enterprise.claims.core.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data-access port over {@code coreapp.policies}. */
public interface PolicyJpaRepository extends JpaRepository<PolicyJpaEntity, UUID> {

  Optional<PolicyJpaEntity> findByPolicyNumber(String policyNumber);

  boolean existsByPolicyNumber(String policyNumber);
}
