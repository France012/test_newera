package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Role;

/**
 * API representation of a role (blueprint §7.1, {@code GET /api/v1/roles}).
 *
 * @param code business code, e.g. {@code CLAIMS_OFFICER}
 * @param name Italian display name, e.g. {@code Istruttore}
 * @param description optional explanation, may be {@code null}
 */
public record RoleResponse(String code, String name, String description) {

  /**
   * @param role domain role to expose
   * @return the API representation
   */
  public static RoleResponse from(Role role) {
    return new RoleResponse(role.code(), role.name(), role.description());
  }
}
