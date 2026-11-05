package it.enterprise.claims.core.application;

import it.enterprise.claims.core.application.dto.RoleResponse;
import it.enterprise.claims.core.domain.port.RoleRepositoryPort;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read use case over the role reference data ({@code coreapp.roles}).
 *
 * <p>Kept as a service (and not called from the controller) so the web layer never depends on an
 * outbound port: the hexagonal boundary stays intact even for a one-line read.
 */
@Service
public class RoleService {

  private final RoleRepositoryPort roleRepository;

  /**
   * @param roleRepository read-only access to the role reference data
   */
  public RoleService(RoleRepositoryPort roleRepository) {
    this.roleRepository = roleRepository;
  }

  /**
   * @return every role, ordered by code
   */
  @Transactional(readOnly = true)
  public List<RoleResponse> findAll() {
    return roleRepository.findAll().stream().map(RoleResponse::from).toList();
  }
}
