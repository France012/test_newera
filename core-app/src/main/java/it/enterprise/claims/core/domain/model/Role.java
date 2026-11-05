package it.enterprise.claims.core.domain.model;

import java.util.Objects;

/**
 * An application role (Italian claims domain: <em>ruolo</em>), reference data of {@code
 * coreapp.roles}.
 *
 * <p>The code is the identity: it is used by {@code actor_roles.role_code}, {@code
 * claim_assignments.role_code} and by the API ({@code PUT
 * /api/v1/claims/{claimId}/assignments/{roleCode}}), so a surrogate key would only add a join.
 * Rows are written by the migration only; the application reads them.
 *
 * @param code business code, e.g. {@code CLAIMS_OFFICER}
 * @param name Italian display name, e.g. {@code Istruttore}
 * @param description optional explanation, may be {@code null}
 */
public record Role(String code, String name, String description) {

  /** Compact constructor: enforces the invariants the database also enforces. */
  public Role {
    Objects.requireNonNull(code, "code is required");
    Objects.requireNonNull(name, "name is required");
  }
}
