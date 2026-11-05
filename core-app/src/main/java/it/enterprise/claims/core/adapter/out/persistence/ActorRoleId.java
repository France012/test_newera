package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key of {@code coreapp.actor_roles} ({@code actor_id}, {@code role_code}).
 *
 * <p>The natural key is the meaningful one: it prevents the same role from being granted twice to
 * the same actor, and revoke/re-grant is expressed by {@code revoked_at} instead of a new row.
 */
@Embeddable
public class ActorRoleId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private UUID actorId;

  @Column(name = "role_code", nullable = false, updatable = false, length = 32)
  private String roleCode;

  /** Required by JPA. */
  protected ActorRoleId() {}

  public ActorRoleId(UUID actorId, String roleCode) {
    this.actorId = actorId;
    this.roleCode = roleCode;
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

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ActorRoleId that)) {
      return false;
    }
    return Objects.equals(actorId, that.actorId) && Objects.equals(roleCode, that.roleCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(actorId, roleCode);
  }

  @Override
  public String toString() {
    return "ActorRoleId{actorId=" + actorId + ", roleCode='" + roleCode + "'}";
  }
}
