package it.enterprise.claims.core.adapter.out.persistence;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.claims} (migrations {@code V1__init_claims.sql} and {@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p>{@code status} is stored as {@link EnumType#STRING} and validated by the {@code
 * ck_claims_status} CHECK constraint; {@code version} is the optimistic-locking counter.
 */
@Entity
@Table(
    name = "claims",
    schema = "coreapp",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_claims_claim_number", columnNames = "claim_number"))
public class ClaimJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_number", nullable = false, length = 32)
  private String claimNumber;

  @Column(name = "policy_id", nullable = false)
  private UUID policyId;

  @Column(name = "claim_type", nullable = false, length = 32)
  private String claimType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private ClaimStatus status = ClaimStatus.DRAFT;

  @Column(name = "description", length = 2000)
  private String description;

  @Column(name = "incident_date", nullable = false)
  private LocalDate incidentDate;

  @Column(name = "opened_at", nullable = false, updatable = false)
  private OffsetDateTime openedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /*
   * Reporting timestamps added by V2__claim_workflow_actors_audit.sql. They are maintained by the
   * application on the transition into CLOSED / ARCHIVED and cross-checked by
   * ck_claims_closed_at_requires_closed_status / ck_claims_archived_at_requires_archived_status.
   */
  @Column(name = "closed_at")
  private OffsetDateTime closedAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** Required by JPA. */
  protected ClaimJpaEntity() {}

  public ClaimJpaEntity(
      UUID id,
      String claimNumber,
      UUID policyId,
      String claimType,
      String description,
      LocalDate incidentDate,
      ClaimStatus status) {
    this.id = id;
    this.claimNumber = claimNumber;
    this.policyId = policyId;
    this.claimType = claimType;
    this.description = description;
    this.incidentDate = incidentDate;
    this.status = status == null ? ClaimStatus.DRAFT : status;
  }

  @PrePersist
  void onCreate() {
    OffsetDateTime now = OffsetDateTime.now();
    if (openedAt == null) {
      openedAt = now;
    }
    if (updatedAt == null) {
      updatedAt = openedAt;
    }
    if (status == null) {
      status = ClaimStatus.DRAFT;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getClaimNumber() {
    return claimNumber;
  }

  public void setClaimNumber(String claimNumber) {
    this.claimNumber = claimNumber;
  }

  public UUID getPolicyId() {
    return policyId;
  }

  public void setPolicyId(UUID policyId) {
    this.policyId = policyId;
  }

  public String getClaimType() {
    return claimType;
  }

  public void setClaimType(String claimType) {
    this.claimType = claimType;
  }

  public ClaimStatus getStatus() {
    return status;
  }

  public void setStatus(ClaimStatus status) {
    this.status = status;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public LocalDate getIncidentDate() {
    return incidentDate;
  }

  public void setIncidentDate(LocalDate incidentDate) {
    this.incidentDate = incidentDate;
  }

  public OffsetDateTime getOpenedAt() {
    return openedAt;
  }

  public void setOpenedAt(OffsetDateTime openedAt) {
    this.openedAt = openedAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public OffsetDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(OffsetDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public OffsetDateTime getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(OffsetDateTime archivedAt) {
    this.archivedAt = archivedAt;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClaimJpaEntity that)) {
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
    return "ClaimJpaEntity{id="
        + id
        + ", claimNumber='"
        + claimNumber
        + "', status="
        + status
        + "}";
  }
}
