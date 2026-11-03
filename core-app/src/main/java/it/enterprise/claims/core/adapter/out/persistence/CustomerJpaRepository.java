package it.enterprise.claims.core.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data-access port over {@code coreapp.customers}. */
public interface CustomerJpaRepository extends JpaRepository<CustomerJpaEntity, UUID> {

  Optional<CustomerJpaEntity> findByFiscalCode(String fiscalCode);
}
