package it.enterprise.claims.core.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An insurance policy ({@code coreapp.policies}). Claims always reference exactly one policy.
 *
 * @param id identifier
 * @param policyNumber business key, unique and used as the lookup path of {@code POST /claims}
 * @param customerId owner of the policy
 * @param productCode product the policy belongs to
 * @param validFrom first day of cover (inclusive)
 * @param validTo last day of cover (inclusive)
 * @param premium premium amount
 */
public record Policy(
    UUID id,
    String policyNumber,
    UUID customerId,
    String productCode,
    LocalDate validFrom,
    LocalDate validTo,
    BigDecimal premium) {

  /** Compact constructor enforcing non-null identifiers and keys. */
  public Policy {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(policyNumber, "policyNumber is required");
    Objects.requireNonNull(customerId, "customerId is required");
    Objects.requireNonNull(productCode, "productCode is required");
    Objects.requireNonNull(validFrom, "validFrom is required");
    Objects.requireNonNull(validTo, "validTo is required");
    Objects.requireNonNull(premium, "premium is required");
  }

  /**
   * @param on date to check
   * @return {@code true} if the policy covers the given date
   */
  public boolean covers(LocalDate on) {
    return on != null && !on.isBefore(validFrom) && !on.isAfter(validTo);
  }
}
