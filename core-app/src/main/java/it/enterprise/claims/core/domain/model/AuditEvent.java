package it.enterprise.claims.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the append-only audit trail ({@code coreapp.audit_events}; decision A5/DD-11).
 *
 * <p>Immutable by construction (a record) and append-only by port design: {@code AuditEventPort}
 * exposes {@code append} plus finders and nothing else, so an update or a delete of the trail does
 * not compile.
 *
 * <p>{@code entityType}/{@code entityId} are polymorphic (no foreign key, index only): the same
 * table records facts about claims, actors and assignments.
 *
 * @param id identifier generated in the application layer
 * @param claimId claim the event belongs to, may be {@code null} for actor-level events
 * @param actorId actor who performed the action, may be {@code null} for system/legacy events
 * @param eventType business event type
 * @param entityType polymorphic entity type, e.g. {@code CLAIM}, {@code ACTOR}
 * @param entityId identifier of the entity the event is about
 * @param outcome {@code SUCCESS} or {@code FAILURE}
 * @param occurredAt timestamp of the fact
 * @param requestId correlation id of the originating request, may be {@code null}
 * @param details free structured payload (before/after, reason), may be {@code null}
 */
public record AuditEvent(
    UUID id,
    UUID claimId,
    UUID actorId,
    AuditEventType eventType,
    String entityType,
    UUID entityId,
    String outcome,
    OffsetDateTime occurredAt,
    String requestId,
    Map<String, Object> details) {

  /** Outcome of a recorded fact. */
  public static final String SUCCESS = "SUCCESS";

  /** Outcome of a rejected/failed attempt that is still worth recording. */
  public static final String FAILURE = "FAILURE";

  /** Compact constructor: enforces the invariants the database also enforces. */
  public AuditEvent {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(eventType, "eventType is required");
    Objects.requireNonNull(entityType, "entityType is required");
    Objects.requireNonNull(entityId, "entityId is required");
    Objects.requireNonNull(occurredAt, "occurredAt is required");
    if (!SUCCESS.equals(outcome) && !FAILURE.equals(outcome)) {
      throw new IllegalArgumentException("outcome must be " + SUCCESS + " or " + FAILURE);
    }
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
