package it.enterprise.claims.core.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.core.application.RoleService;
import it.enterprise.claims.core.application.dto.RoleResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role endpoints, base path {@code /api/v1/roles} (blueprint §7.1).
 *
 * <p>Reference data only: the roles are seeded by the Flyway migration, so the endpoint is
 * read-only by construction.
 */
@RestController
@RequestMapping(value = "/api/v1/roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Roles", description = "Role reference data (Istruttore, Perito, Liquidatore, ...)")
public class RoleController {

  private final RoleService roleService;

  /**
   * @param roleService read use case over the role reference data
   */
  public RoleController(RoleService roleService) {
    this.roleService = roleService;
  }

  /**
   * @return every role, ordered by code
   */
  @GetMapping
  @Operation(summary = "List the role reference data")
  public List<RoleResponse> list() {
    return roleService.findAll();
  }
}
