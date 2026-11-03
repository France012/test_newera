package it.enterprise.claims.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Claim as exposed by the BFF (and as returned by {@code core-app} {@code ClaimResponse}).
 *
 * <p>{@code status} is a {@code String} on purpose: a status added to the core-app enum later must
 * not break deserialisation here, and the BFF has no business re-implementing the claim state
 * machine. Unknown properties are ignored so core-app can evolve without lock-step releases.
 *
 * @param id claim identifier
 * @param claimNumber business key
 * @param policyNumber policy the claim belongs to
 * @param customerId owner of the policy
 * @param claimType classification of the claim
 * @param status lifecycle state as a string
 * @param description free-text description
 * @param incidentDate date of the incident
 * @param openedAt opening timestamp
 * @param updatedAt last modification timestamp
 * @param documentIds ids of attached documents
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimSummaryResponse(
    UUID id,
    String claimNumber,
    String policyNumber,
    UUID customerId,
    String claimType,
    String status,
    String description,
    LocalDate incidentDate,
    OffsetDateTime openedAt,
    OffsetDateTime updatedAt,
    List<UUID> documentIds) {}
