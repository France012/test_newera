package it.enterprise.claims.core.domain.port;

import it.enterprise.claims.core.domain.model.Role;
import java.util.List;

/**
 * Outbound port over the {@code coreapp.roles} reference data.
 *
 * <p>Read-only by design: the only writer of that table is the Flyway migration, so the application
 * cannot drift from the seeded vocabulary.
 */
public interface RoleRepositoryPort {

  /**
   * @return every role, ordered by code
   */
  List<Role> findAll();

  /**
   * @param code role code to check
   * @return {@code true} when the code exists in the reference table
   */
  boolean existsByCode(String code);
}
