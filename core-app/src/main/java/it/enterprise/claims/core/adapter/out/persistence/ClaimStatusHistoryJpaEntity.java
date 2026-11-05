package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.claim_status_history} (migrations {@code V1__init_claims.sql} and
 * {@code V2__claim_workflow_actors_audit.sql}).
 *
 * <p>Append-only audit trail: rows are never updated. {@code fromStatus} is {@code null} for the
 * opening transition of a claim.
 *
 * <p>Since TK-64FBC8 the {@code (from_status, to_status)} pair is also enforced by PostgreSQL: the
 * composite foreign key {@code fk_claim_status_history_transition} points at {@code
 * coreapp.claim_status_transition}, so an illegal transition is rejected by the database even if
 * the application check is bypassed. The FK is {@code MATCH SIMPLE}, therefore the opening row
 * ({@code from_status = NULL}) is intentionally not checked.
 */
@Entity
@Table(name = "claim_status_history", schema = "coreapp")
public class ClaimStatusHistoryJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 32)
  private ClaimStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 32)
  private ClaimStatus toStatus;

  @Column(name = "note", length = 500)
  private String note;

  /** Author of the transition (V2). {@code null} for rows written before TK-64FBC8. */
  @Column(name = "actor_id", updatable = false)
  private UUID actorId;

  @Column(name = "changed_at", nullable = false, updatable = false)
  private OffsetDateTime changedAt;

  /** Required by JPA. */
  protected ClaimStatusHistoryJpaEntity() {}

  public ClaimStatusHistoryJpaEntity(
      UUID id, UUID claimId, ClaimStatus fromStatus, ClaimStatus toStatus, String note) {
    this.id = id;
    this.claimId = claimId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.note = note;
  }

  public ClaimStatusHistoryJpaEntity(
      UUID id,
      UUID claimId,
      ClaimStatus fromStatus,
      ClaimStatus toStatus,
      String note,
      UUID actorId) {
    this(id, claimId, fromStatus, toStatus, note);
    this.actorId = actorId;
  }

  @PrePersist
  void onCreate() {
    if (changedAt == null) {
      changedAt = OffsetDateTime.now();
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

  public ClaimStatus getFromStatus() {
    return fromStatus;
  }

  public void setFromStatus(ClaimStatus fromStatus) {
    this.fromStatus = fromStatus;
  }

  public ClaimStatus getToStatus() {
    return toStatus;
  }

  public void setToStatus(ClaimStatus toStatus) {
    this.toStatus = toStatus;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public OffsetDateTime getChangedAt() {
    return changedAt;
  }

  public void setChangedAt(OffsetDateTime changedAt) {
    this.changedAt = changedAt;
  }

  public UUID getActorId() {
    return actorId;
  }

  public void setActorId(UUID actorId) {
    this.actorId = actorId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClaimStatusHistoryJpaEntity that)) {
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
    return "ClaimStatusHistoryJpaEntity{id="
        + id
        + ", claimId="
        + claimId
        + ", toStatus="
        + toStatus
        + "}";
  }
}
