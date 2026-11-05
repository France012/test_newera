package it.enterprise.claims.core.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Payload of {@code PUT /api/v1/actors/{actorId}/roles}.
 *
 * <p>Full replacement, idempotent: the request carries the complete set of roles the actor must
 * hold after the call. Grants that are no longer listed are revoked (never deleted), so the history
 * survives.
 *
 * @param roleCodes complete set of role codes, may be empty to revoke everything
 */
public record ActorRolesUpdateRequest(
    @NotNull @Size(max = 10, message = "an actor can hold at most 10 roles") Set<String> roleCodes) {}
