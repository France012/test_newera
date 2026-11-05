package it.enterprise.claims.core.application.exception;

/**
 * Thrown when a role code is not part of the {@code coreapp.roles} reference data - mapped to HTTP
 * 400.
 */
public class UnknownRoleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String roleCode;

  /**
   * @param roleCode the code that does not exist
   */
  public UnknownRoleException(String roleCode) {
    super("Unknown role code '" + roleCode + "'");
    this.roleCode = roleCode;
  }

  /**
   * @return the code that does not exist
   */
  public String getRoleCode() {
    return roleCode;
  }
}
