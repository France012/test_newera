package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.actors} (migration {@code V2__claim_workflow_actors_audit.sql}).
 *
 * <p>An actor is a person/service that operates on claims. {@code subjectRef} is the external IdP
 * subject: it is caller-asserted in phase 1 (ADR-0005, decision A9) and stays unique even after the
 * soft delete, because an identity is reactivated and never re-created.
 *
 * <p>Deletion is logical ({@code deleted_at}): every read path of {@code ActorJpaRepository}
 * filters it out (DD-6). {@code version} is the Hibernate {@code @Version} optimistic-locking
 * counter.
 */
@Entity
@Table(
    name = "actors",
    schema = "coreapp",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_actors_subject_ref", columnNames = "subject_ref"))
public class ActorJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "subject_ref", nullable = false, updatable = false, length = 128)
  private String subjectRef;

  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "actor_type", nullable = false, length = 32)
  private String actorType;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

  /** Required by JPA. */
  protected ActorJpaEntity() {}

  public ActorJpaEntity(
      UUID id, String subjectRef, String displayName, String email, String actorType) {
    this.id = id;
    this.subjectRef = subjectRef;
    this.displayName = displayName;
    this.email = email;
    this.actorType = actorType;
  }

  @PrePersist
  void onCreate() {
    OffsetDateTime now = OffsetDateTime.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = createdAt;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  /**
   * Marks the actor as logically deleted.
   *
   * @param when deletion timestamp, {@code null} means "now"
   */
  public void markDeleted(OffsetDateTime when) {
    this.deletedAt = when == null ? OffsetDateTime.now() : when;
  }

  /**
   * @return {@code true} if the actor has been logically deleted
   */
  public boolean isDeleted() {
    return deletedAt != null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getSubjectRef() {
    return subjectRef;
  }

  public void setSubjectRef(String subjectRef) {
    this.subjectRef = subjectRef;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getActorType() {
    return actorType;
  }

  public void setActorType(String actorType) {
    this.actorType = actorType;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  public OffsetDateTime getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(OffsetDateTime deletedAt) {
    this.deletedAt = deletedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ActorJpaEntity that)) {
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
    return "ActorJpaEntity{id="
        + id
        + ", subjectRef='"
        + subjectRef
        + "', actorType='"
        + actorType
        + "'}";
  }
}
