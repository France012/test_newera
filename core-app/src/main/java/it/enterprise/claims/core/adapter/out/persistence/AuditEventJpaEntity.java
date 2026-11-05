package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping of {@code coreapp.audit_events} (migration {@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p><strong>Append-only</strong> (decision A5/DD-11): the entity is {@link Immutable}, every
 * column is {@code updatable = false}, and {@code AuditEventJpaRepository} exposes no mutator at
 * all. There is deliberately no database trigger (DD-8): hidden DB behaviour is avoided, and
 * immutability is guaranteed by the only writer being the repository port.
 *
 * <p>{@code eventType} and {@code entityType} are validated by a <em>format</em> CHECK ({@code
 * '^[A-Z][A-Z0-9_]{2,63}$'}), not by an enumerated CHECK: a new business event must never require a
 * migration. {@code details} is {@code jsonb} mapped as a JSON string (plan risk R1: fallback to
 * {@code varchar} documented in DD-11).
 */
@Entity
@Immutable
@Table(name = "audit_events", schema = "coreapp")
public class AuditEventJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", updatable = false)
  private UUID claimId;

  @Column(name = "actor_id", updatable = false)
  private UUID actorId;

  @Column(name = "event_type", nullable = false, updatable = false, length = 64)
  private String eventType;

  @Column(name = "entity_type", nullable = false, updatable = false, length = 32)
  private String entityType;

  @Column(name = "entity_id", nullable = false, updatable = false)
  private UUID entityId;

  @Column(name = "outcome", nullable = false, updatable = false, length = 16)
  private String outcome = "SUCCESS";

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "request_id", updatable = false, length = 64)
  private String requestId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "details", updatable = false)
  private String details;

  /** Required by JPA. */
  protected AuditEventJpaEntity() {}

  public AuditEventJpaEntity(
      UUID id,
      UUID claimId,
      UUID actorId,
      String eventType,
      String entityType,
      UUID entityId,
      String outcome,
      String requestId,
      String details) {
    this.id = id;
    this.claimId = claimId;
    this.actorId = actorId;
    this.eventType = eventType;
    this.entityType = entityType;
    this.entityId = entityId;
    this.outcome = outcome == null ? "SUCCESS" : outcome;
    this.requestId = requestId;
    this.details = details;
  }

  @PrePersist
  void onCreate() {
    if (occurredAt == null) {
      occurredAt = OffsetDateTime.now();
    }
    if (outcome == null) {
      outcome = "SUCCESS";
    }
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getClaimId() {
    return claimId;
  }

  public void setClaimId(UUID claimId) {
    this.claimId = claimId;
  }

  public UUID getActorId() {
    return actorId;
  }

  public void setActorId(UUID actorId) {
    this.actorId = actorId;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public void setEntityId(UUID entityId) {
    this.entityId = entityId;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(OffsetDateTime occurredAt) {
    this.occurredAt = occurredAt;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AuditEventJpaEntity that)) {
      return false;
    }
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    return "AuditEventJpaEntity{id="
        + id
        + ", eventType='"
        + eventType
        + "', entityType='"
        + entityType
        + "'}";
  }
}
