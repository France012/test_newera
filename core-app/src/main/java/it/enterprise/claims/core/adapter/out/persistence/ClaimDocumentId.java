package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key of {@code coreapp.claim_documents} ({@code claim_id}, {@code document_id}).
 *
 * <p>Kept as a separate {@link Embeddable} class (instead of a surrogate id) because the natural
 * key is the meaningful one for this link table and it prevents duplicate attachments.
 */
@Embeddable
public class ClaimDocumentId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  /** Required by JPA. */
  protected ClaimDocumentId() {}

  public ClaimDocumentId(UUID claimId, UUID documentId) {
    this.claimId = claimId;
    this.documentId = documentId;
  }

  public UUID getClaimId() {
    return claimId;
  }

  public void setClaimId(UUID claimId) {
    this.claimId = claimId;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public void setDocumentId(UUID documentId) {
    this.documentId = documentId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClaimDocumentId that)) {
      return false;
    }
    return Objects.equals(claimId, that.claimId) && Objects.equals(documentId, that.documentId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(claimId, documentId);
  }

  @Override
  public String toString() {
    return "ClaimDocumentId{claimId=" + claimId + ", documentId=" + documentId + "}";
  }
}
