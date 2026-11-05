package it.enterprise.claims.core.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.enterprise.claims.core.domain.model.AuditEvent;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.port.AuditEventPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter implementing {@link AuditEventPort} over {@code coreapp.audit_events}.
 *
 * <p>Append-only (decision A5/DD-11): the adapter exposes no update and no delete, and the entity
 * is mapped with {@code @Immutable}, so the trail can only grow.
 *
 * <p>{@code details} is a {@code jsonb} column: it is serialised from the domain {@code Map} on
 * write and parsed back on read, so the application never handles PostgreSQL-specific types.
 */
@Repository
public class AuditEventPersistenceAdapter implements AuditEventPort {

  private static final TypeReference<Map<String, Object>> DETAILS_TYPE = new TypeReference<>() {};

  private final AuditEventJpaRepository auditEventJpaRepository;
  private final ObjectMapper objectMapper;

  /**
   * @param auditEventJpaRepository access to {@code coreapp.audit_events}
   * @param objectMapper mapper used to translate {@code details} to and from {@code jsonb}
   */
  public AuditEventPersistenceAdapter(
      AuditEventJpaRepository auditEventJpaRepository, ObjectMapper objectMapper) {
    this.auditEventJpaRepository = auditEventJpaRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public AuditEvent append(AuditEvent event) {
    AuditEventJpaEntity entity =
        new AuditEventJpaEntity(
            event.id(),
            event.claimId(),
            event.actorId(),
            event.eventType().name(),
            event.entityType(),
            event.entityId(),
            event.outcome(),
            event.requestId(),
            serializeDetails(event.details()));
    entity.setOccurredAt(event.occurredAt());
    return toDomain(auditEventJpaRepository.save(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AuditEvent> findByClaimId(UUID claimId, AuditEventType eventType, Pageable pageable) {
    Page<AuditEventJpaEntity> page =
        eventType == null
            ? auditEventJpaRepository.findByClaimId(claimId, pageable)
            : auditEventJpaRepository.findByClaimIdAndEventType(
                claimId, eventType.name(), pageable);
    return page.map(this::toDomain);
  }

  private String serializeDetails(Map<String, Object> details) {
    if (details == null || details.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(details);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialise the audit event details", ex);
    }
  }

  private Map<String, Object> parseDetails(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, DETAILS_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to parse the audit event details", ex);
    }
  }

  private AuditEvent toDomain(AuditEventJpaEntity entity) {
    return new AuditEvent(
        entity.getId(),
        entity.getClaimId(),
        entity.getActorId(),
        AuditEventType.valueOf(entity.getEventType()),
        entity.getEntityType(),
        entity.getEntityId(),
        entity.getOutcome(),
        entity.getOccurredAt(),
        entity.getRequestId(),
        parseDetails(entity.getDetails()));
  }
}
