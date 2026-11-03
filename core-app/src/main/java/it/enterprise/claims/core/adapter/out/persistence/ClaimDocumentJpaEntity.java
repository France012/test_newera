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
 * JPA mapping of {@code coreapp.claim_documents} (migration {@code V1__init_claims.sql}).
 *
 * <p>{@code document_id} is a logical reference to document-service: no cross-service foreign key
 * exists by design (database-per-service).
 */
@Entity
@Table(name = "claim_documents", schema = "coreapp")
public class ClaimDocumentJpaEntity {

  @EmbeddedId private ClaimDocumentId id;

  @Column(name = "linked_at", nullable = false, updatable = false)
  private OffsetDateTime linkedAt;

  /** Required by JPA. */
  protected ClaimDocumentJpaEntity() {}

  public ClaimDocumentJpaEntity(ClaimDocumentId id) {
    this.id = id;
  }

  public ClaimDocumentJpaEntity(UUID claimId, UUID documentId) {
    this.id = new ClaimDocumentId(claimId, documentId);
  }

  @PrePersist
  void onCreate() {
    if (linkedAt == null) {
      linkedAt = OffsetDateTime.now();
    }
  }

  public ClaimDocumentId getId() {
    return id;
  }

  public void setId(ClaimDocumentId id) {
    this.id = id;
  }

  public OffsetDateTime getLinkedAt() {
    return linkedAt;
  }

  public void setLinkedAt(OffsetDateTime linkedAt) {
    this.linkedAt = linkedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClaimDocumentJpaEntity that)) {
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
    return "ClaimDocumentJpaEntity{id=" + id + "}";
  }
}
