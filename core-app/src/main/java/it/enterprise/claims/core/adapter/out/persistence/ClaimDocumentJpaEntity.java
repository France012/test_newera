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
 * JPA mapping of {@code coreapp.claim_documents} (migrations {@code V1__init_claims.sql} and {@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p>{@code document_id} is a logical reference to document-service: no cross-service foreign key
 * exists by design (database-per-service).
 *
 * <p>The relation is softly deleted ({@code unlinked_at}): the row survives so the attachment
 * history stays auditable, and the active links are served by the partial index {@code
 * idx_claim_documents_active}.
 */
@Entity
@Table(name = "claim_documents", schema = "coreapp")
public class ClaimDocumentJpaEntity {

  @EmbeddedId private ClaimDocumentId id;

  @Column(name = "linked_at", nullable = false, updatable = false)
  private OffsetDateTime linkedAt;

  /** Actor who attached the document (V2, nullable for rows written before TK-64FBC8). */
  @Column(name = "linked_by_actor_id", updatable = false)
  private UUID linkedByActorId;

  /** Soft un-link marker (V2): non-null means the document is no longer attached. */
  @Column(name = "unlinked_at")
  private OffsetDateTime unlinkedAt;

  /** Required by JPA. */
  protected ClaimDocumentJpaEntity() {}

  public ClaimDocumentJpaEntity(ClaimDocumentId id) {
    this.id = id;
  }

  public ClaimDocumentJpaEntity(UUID claimId, UUID documentId) {
    this.id = new ClaimDocumentId(claimId, documentId);
  }

  public ClaimDocumentJpaEntity(UUID claimId, UUID documentId, UUID linkedByActorId) {
    this(claimId, documentId);
    this.linkedByActorId = linkedByActorId;
  }

  @PrePersist
  void onCreate() {
    if (linkedAt == null) {
      linkedAt = OffsetDateTime.now();
    }
  }

  /**
   * Soft un-link of the document from the claim.
   *
   * @param when un-link timestamp, {@code null} means "now"
   */
  public void unlink(OffsetDateTime when) {
    this.unlinkedAt = when == null ? OffsetDateTime.now() : when;
  }

  /**
   * @return {@code true} if the document is still attached to the claim
   */
  public boolean isActive() {
    return unlinkedAt == null;
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

  public UUID getLinkedByActorId() {
    return linkedByActorId;
  }

  public void setLinkedByActorId(UUID linkedByActorId) {
    this.linkedByActorId = linkedByActorId;
  }

  public OffsetDateTime getUnlinkedAt() {
    return unlinkedAt;
  }

  public void setUnlinkedAt(OffsetDateTime unlinkedAt) {
    this.unlinkedAt = unlinkedAt;
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
