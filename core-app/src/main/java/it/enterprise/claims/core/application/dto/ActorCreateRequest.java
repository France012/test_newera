package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.ActorType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Payload of {@code POST /api/v1/actors}.
 *
 * <p>The validation mirrors the CHECK constraints of {@code coreapp.actors} (migration {@code
 * V2__claim_workflow_actors_audit.sql}) so a malformed actor is rejected with a field-level 400
 * before touching the database.
 *
 * @param subjectRef external IdP subject; unique, caller-asserted in phase 1 (ADR-0005)
 * @param displayName human readable name
 * @param email contact address, may be {@code null}
 * @param actorType nature of the actor
 * @param roleCodes roles to grant at creation, may be {@code null} or empty
 */
public record ActorCreateRequest(
    @NotBlank @Size(min = 3, max = 128)
        @Pattern(
            regexp = "^[A-Za-z0-9._:@|/-]{3,128}$",
            message = "must contain only letters, digits and ._:@|/- (3-128 characters)")
        String subjectRef,
    @NotBlank @Size(max = 200) String displayName,
    @Email @Size(max = 255) String email,
    @NotNull ActorType actorType,
    @Size(max = 10, message = "an actor can hold at most 10 roles") Set<String> roleCodes) {}
