package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API representation of a policy (blueprint §2.3).
 *
 * @param id identifier
 * @param policyNumber business key
 * @param customerId owner of the policy
 * @param productCode product the policy belongs to
 * @param validFrom first day of cover
 * @param validTo last day of cover
 * @param premium premium amount
 */
public record PolicyResponse(
    UUID id,
    String policyNumber,
    UUID customerId,
    String productCode,
    LocalDate validFrom,
    LocalDate validTo,
    BigDecimal premium) {

  /**
   * @param policy domain policy to expose
   * @return the API representation
   */
  public static PolicyResponse from(Policy policy) {
    return new PolicyResponse(
        policy.id(),
        policy.policyNumber(),
        policy.customerId(),
        policy.productCode(),
        policy.validFrom(),
        policy.validTo(),
        policy.premium());
  }
}
