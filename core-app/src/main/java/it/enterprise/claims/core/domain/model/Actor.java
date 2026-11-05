package it.enterprise.claims.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An actor: a person or system that operates on claims ({@code coreapp.actors}).
 *
 * <p>Pure domain type (no JPA/JSON annotations, ADR-0002). {@code subjectRef} is the external IdP
 * subject: in phase 1 it is caller-asserted (ADR-0005, decision A9) and it is unique for the whole
 * lifetime of the identity, soft-deleted rows included.
 *
 * <p>{@code roleCodes} are resolved by the persistence adapter from {@code coreapp.actor_roles}
 * (only the grants that are not revoked); the actor row itself does not store them.
 *
 * @param id identifier generated in the application layer
 * @param subjectRef external IdP subject, unique forever, caller-asserted in phase 1
 * @param displayName human readable name
 * @param email contact address, may be {@code null}
 * @param type nature of the actor
 * @param enabled {@code false} blocks new assignments without losing history
 * @param roleCodes codes of the roles currently granted, never {@code null}
 * @param createdAt creation timestamp
 * @param updatedAt last modification timestamp
 */
public record Actor(
    UUID id,
    String subjectRef,
    String displayName,
    String email,
    ActorType type,
    boolean enabled,
    Set<String> roleCodes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /** Compact constructor: enforces the invariants the database also enforces. */
  public Actor {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(subjectRef, "subjectRef is required");
    Objects.requireNonNull(displayName, "displayName is required");
    Objects.requireNonNull(type, "type is required");
    roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
  }

  /**
   * @param newRoleCodes codes of the roles granted after the update
   * @return a copy of this actor with the given roles
   */
  public Actor withRoleCodes(Set<String> newRoleCodes) {
    return new Actor(
        id, subjectRef, displayName, email, type, enabled, newRoleCodes, createdAt, updatedAt);
  }
}
