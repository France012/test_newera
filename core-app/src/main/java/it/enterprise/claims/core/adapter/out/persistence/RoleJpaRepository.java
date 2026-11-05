package it.enterprise.claims.core.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-access port over {@code coreapp.roles} (reference data seeded by {@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p>Query derivation only, no business rules: roles are read-only for the application, the only
 * writer is the migration.
 */
public interface RoleJpaRepository extends JpaRepository<RoleJpaEntity, String> {

  /**
   * @return every role, ordered by code (stable order for the {@code GET /api/v1/roles} response)
   */
  List<RoleJpaEntity> findAllByOrderByCodeAsc();

  boolean existsByCode(String code);
}
