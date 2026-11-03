package it.enterprise.claims.core.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A customer ({@code coreapp.customers}): the policy holder a claim ultimately belongs to.
 *
 * <p>Read model only in phase 1 - there is no customer endpoint in the API contract yet, the record
 * exists because the domain must be able to express the policy owner without borrowing persistence
 * types.
 *
 * @param id identifier
 * @param fiscalCode fiscal code, unique business key
 * @param fullName display name
 * @param email contact email, may be {@code null}
 */
public record Customer(UUID id, String fiscalCode, String fullName, String email) {

  /** Compact constructor enforcing non-null identifiers and keys. */
  public Customer {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(fiscalCode, "fiscalCode is required");
    Objects.requireNonNull(fullName, "fullName is required");
  }
}
