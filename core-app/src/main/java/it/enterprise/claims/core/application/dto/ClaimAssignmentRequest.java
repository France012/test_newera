package it.enterprise.claims.core.application.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Payload of {@code PUT /api/v1/claims/{claimId}/assignments/{roleCode}}.
 *
 * @param actorId actor who becomes the holder of the role on the claim
 */
public record ClaimAssignmentRequest(@NotNull UUID actorId) {}
