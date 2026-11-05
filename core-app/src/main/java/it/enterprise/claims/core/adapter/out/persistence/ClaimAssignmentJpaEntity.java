package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.claim_assignments} (migration {@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p>Attore + ruolo su una pratica. The "at most one active holder per (claim, role)" rule is
 * enforced by the partial unique index {@code uk_claim_assignments_active_role}, so the adapter
 * must close the previous row ({@link #unassign(OffsetDateTime)}) before inserting the new one.
 *
 * <p>Foreign keys are mapped as scalar {@link UUID} fields, never {@code @ManyToOne} (DD-3).
 */
@Entity
@Table(name = "claim_assignments", schema = "coreapp")
public class ClaimAssignmentJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private UUID actorId;

  @Column(name = "role_code", nullable = false, updatable = false, length = 32)
  private String roleCode;

  @Column(name = "assigned_at", nullable = false, updatable = false)
  private OffsetDateTime assignedAt;

  @Column(name = "assigned_by", updatable = false)
  private UUID assignedBy;

  @Column(name = "unassigned_at")
  private OffsetDateTime unassignedAt;

  /** Required by JPA. */
  protected ClaimAssignmentJpaEntity() {}

  public ClaimAssignmentJpaEntity(
      UUID id, UUID claimId, UUID actorId, String roleCode, UUID assignedBy) {
    this.id = id;
    this.claimId = claimId;
    this.actorId = actorId;
    this.roleCode = roleCode;
    this.assignedBy = assignedBy;
  }

  @PrePersist
  void onCreate() {
    if (assignedAt == null) {
      assignedAt = OffsetDateTime.now();
    }
  }

  /**
   * Soft un-assign: the row stays for the audit trail.
   *
   * @param when un-assignment timestamp, {@code null} means "now"
   */
  public void unassign(OffsetDateTime when) {
    this.unassignedAt = when == null ? OffsetDateTime.now() : when;
  }

  /**
   * @return {@code true} if the assignment is still active
   */
  public boolean isActive() {
    return unassignedAt == null;
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

  public String getRoleCode() {
    return roleCode;
  }

  public void setRoleCode(String roleCode) {
    this.roleCode = roleCode;
  }

  public OffsetDateTime getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(OffsetDateTime assignedAt) {
    this.assignedAt = assignedAt;
  }

  public UUID getAssignedBy() {
    return assignedBy;
  }

  public void setAssignedBy(UUID assignedBy) {
    this.assignedBy = assignedBy;
  }

  public OffsetDateTime getUnassignedAt() {
    return unassignedAt;
  }

  public void setUnassignedAt(OffsetDateTime unassignedAt) {
    this.unassignedAt = unassignedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClaimAssignmentJpaEntity that)) {
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
    return "ClaimAssignmentJpaEntity{id="
        + id
        + ", claimId="
        + claimId
        + ", roleCode='"
        + roleCode
        + "'}";
  }
}
