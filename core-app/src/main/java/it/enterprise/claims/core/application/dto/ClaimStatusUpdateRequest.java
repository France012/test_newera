package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code PATCH /api/v1/claims/{id}/status}.
 *
 * @param status requested target state, validated against the {@link ClaimStatus} state machine
 * @param note optional reason stored in the audit trail
 */
public record ClaimStatusUpdateRequest(@NotNull ClaimStatus status, @Size(max = 500) String note) {}
