package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Claim;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API representation of a claim (blueprint §2.3).
 *
 * @param id identifier
 * @param claimNumber business key
 * @param policyNumber policy the claim belongs to
 * @param customerId owner of that policy
 * @param claimType classification of the claim
 * @param status current lifecycle state
 * @param description free-text description, may be {@code null}
 * @param incidentDate date of the incident
 * @param openedAt opening timestamp
 * @param updatedAt last modification timestamp
 * @param documentIds ids of the documents attached through document-service
 */
public record ClaimResponse(
    UUID id,
    String claimNumber,
    String policyNumber,
    UUID customerId,
    String claimType,
    ClaimStatus status,
    String description,
    LocalDate incidentDate,
    OffsetDateTime openedAt,
    OffsetDateTime updatedAt,
    List<UUID> documentIds) {

  /**
   * @param claim domain claim to expose
   * @return the API representation of the claim
   */
  public static ClaimResponse from(Claim claim) {
    return new ClaimResponse(
        claim.id(),
        claim.claimNumber(),
        claim.policyNumber(),
        claim.customerId(),
        claim.claimType(),
        claim.status(),
        claim.description(),
        claim.incidentDate(),
        claim.openedAt(),
        claim.updatedAt(),
        claim.documentIds());
  }
}
