package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.actor_roles} (migration {@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p>Grant/revoke model: a revoked role keeps its row and gets {@code revoked_at}; a re-grant sets
 * it back to {@code null}. The intermediate history lives in {@code coreapp.audit_events}, not here
 * (one source of truth per fact).
 */
@Entity
@Table(name = "actor_roles", schema = "coreapp")
public class ActorRoleJpaEntity {

  @EmbeddedId private ActorRoleId id;

  @Column(name = "granted_at", nullable = false, updatable = false)
  private OffsetDateTime grantedAt;

  @Column(name = "granted_by", updatable = false)
  private UUID grantedBy;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  /** Required by JPA. */
  protected ActorRoleJpaEntity() {}

  public ActorRoleJpaEntity(ActorRoleId id) {
    this.id = id;
  }

  public ActorRoleJpaEntity(UUID actorId, String roleCode, UUID grantedBy) {
    this.id = new ActorRoleId(actorId, roleCode);
    this.grantedBy = grantedBy;
  }

  @PrePersist
  void onCreate() {
    if (grantedAt == null) {
      grantedAt = OffsetDateTime.now();
    }
  }

  /**
   * Revokes the role without deleting the row.
   *
   * @param when revocation timestamp, {@code null} means "now"
   */
  public void revoke(OffsetDateTime when) {
    this.revokedAt = when == null ? OffsetDateTime.now() : when;
  }

  /** Re-activates a revoked grant. */
  public void regrant() {
    this.revokedAt = null;
  }

  /**
   * @return {@code true} if the grant is currently active
   */
  public boolean isActive() {
    return revokedAt == null;
  }

  public ActorRoleId getId() {
    return id;
  }

  public void setId(ActorRoleId id) {
    this.id = id;
  }

  public OffsetDateTime getGrantedAt() {
    return grantedAt;
  }

  public void setGrantedAt(OffsetDateTime grantedAt) {
    this.grantedAt = grantedAt;
  }

  public UUID getGrantedBy() {
    return grantedBy;
  }

  public void setGrantedBy(UUID grantedBy) {
    this.grantedBy = grantedBy;
  }

  public OffsetDateTime getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(OffsetDateTime revokedAt) {
    this.revokedAt = revokedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ActorRoleJpaEntity that)) {
      return false;
    }
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    return "ActorRoleJpaEntity{id=" + id + ", revokedAt=" + revokedAt + "}";
  }
}
