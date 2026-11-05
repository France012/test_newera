package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.AuditEvent;
import it.enterprise.claims.core.domain.model.AuditEventType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * API representation of one audit event (blueprint §7.1).
 *
 * @param id identifier of the event
 * @param claimId claim the event belongs to, may be {@code null}
 * @param actorId actor who performed the action, may be {@code null}
 * @param eventType business event type
 * @param entityType polymorphic entity type
 * @param entityId identifier of the entity the event is about
 * @param outcome {@code SUCCESS} or {@code FAILURE}
 * @param occurredAt timestamp of the fact
 * @param requestId correlation id of the originating request, may be {@code null}
 * @param details free structured payload, never {@code null}
 */
public record AuditEventResponse(
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

  /**
   * @param event domain event to expose
   * @return the API representation
   */
  public static AuditEventResponse from(AuditEvent event) {
    return new AuditEventResponse(
        event.id(),
        event.claimId(),
        event.actorId(),
        event.eventType(),
        event.entityType(),
        event.entityId(),
        event.outcome(),
        event.occurredAt(),
        event.requestId(),
        event.details());
  }
}
