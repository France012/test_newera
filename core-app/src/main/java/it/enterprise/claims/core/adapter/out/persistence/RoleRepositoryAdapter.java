package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.model.Role;
import it.enterprise.claims.core.domain.port.RoleRepositoryPort;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter implementing {@link RoleRepositoryPort} over {@code coreapp.roles}.
 *
 * <p>Read-only: the reference data is written by the Flyway migration only, which is what keeps the
 * role vocabulary and the seeded transitions consistent with the code.
 */
@Repository
public class RoleRepositoryAdapter implements RoleRepositoryPort {

  private final RoleJpaRepository roleJpaRepository;

  /**
   * @param roleJpaRepository access to {@code coreapp.roles}
   */
  public RoleRepositoryAdapter(RoleJpaRepository roleJpaRepository) {
    this.roleJpaRepository = roleJpaRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Role> findAll() {
    return roleJpaRepository.findAllByOrderByCodeAsc().stream()
        .map(entity -> new Role(entity.getCode(), entity.getName(), entity.getDescription()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsByCode(String code) {
    return code != null && roleJpaRepository.existsByCode(code);
  }
}
