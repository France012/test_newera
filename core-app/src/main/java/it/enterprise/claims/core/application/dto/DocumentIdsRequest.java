package it.enterprise.claims.core.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code POST /api/v1/claims/{id}/documents}: attaches document-service ids to a claim.
 *
 * @param documentIds identifiers returned by document-service; the list must not be empty
 */
public record DocumentIdsRequest(@NotNull @Size(min = 1, max = 50) List<UUID> documentIds) {}
