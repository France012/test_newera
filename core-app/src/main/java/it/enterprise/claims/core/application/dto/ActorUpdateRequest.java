package it.enterprise.claims.core.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code PATCH /api/v1/actors/{actorId}}.
 *
 * <p>Patch semantics: a {@code null} field is left untouched. Roles and {@code subjectRef} are
 * deliberately not updatable here (roles have their own endpoint, the subject is an identity).
 *
 * @param displayName new display name, may be {@code null}
 * @param email new contact address, may be {@code null}
 * @param enabled new enabled flag, may be {@code null}
 */
public record ActorUpdateRequest(
    @Size(max = 200) String displayName, @Email @Size(max = 255) String email, Boolean enabled) {}
