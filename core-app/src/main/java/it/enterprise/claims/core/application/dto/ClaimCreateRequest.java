package it.enterprise.claims.core.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload of {@code POST /api/v1/claims} (blueprint §2.3).
 *
 * @param policyNumber policy the claim refers to; the policy must exist
 * @param customerId owner of the policy. Optional: when provided it must match the policy owner,
 *     otherwise the request is rejected. It is accepted so the frontend can assert the identity it
 *     is working on, but the authoritative value always comes from the policy
 * @param claimType classification of the claim
 * @param description free-text description
 * @param incidentDate date of the incident, cannot be in the future
 */
public record ClaimCreateRequest(
    @NotBlank @Size(max = 32) String policyNumber,
    UUID customerId,
    @NotBlank @Size(max = 32) String claimType,
    @Size(max = 2000) String description,
    @NotNull @PastOrPresent LocalDate incidentDate) {}
