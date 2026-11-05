package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * JPA mapping of {@code coreapp.roles} (migration {@code V2__claim_workflow_actors_audit.sql}).
 *
 * <p>Reference data seeded by the migration (Istruttore, Perito, Liquidatore, Revisore,
 * Amministratore). The primary key is the business code: roles are identified by code everywhere
 * (API, {@code actor_roles.role_code}, {@code claim_assignments.role_code}), so a surrogate id
 * would only add a join.
 */
@Entity
@Table(name = "roles", schema = "coreapp")
public class RoleJpaEntity {

  @Id
  @Column(name = "code", nullable = false, updatable = false, length = 32)
  private String code;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  /** Required by JPA. */
  protected RoleJpaEntity() {}

  public RoleJpaEntity(String code, String name, String description) {
    this.code = code;
    this.name = name;
    this.description = description;
  }

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RoleJpaEntity that)) {
      return false;
    }
    return code != null && code.equals(that.code);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(code);
  }

  @Override
  public String toString() {
    return "RoleJpaEntity{code='" + code + "', name='" + name + "'}";
  }
}
