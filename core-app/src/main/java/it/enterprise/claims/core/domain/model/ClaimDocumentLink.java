package it.enterprise.claims.core.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the {@code coreapp.claim_documents} link table (pratica ⇄ documento).
 *
 * <p>{@code documentId} is a logical reference to document-service: there is no cross-service
 * foreign key (database-per-service), so the link is validated by the application when the document
 * is attached.
 *
 * <p>The relation is <em>softly</em> deleted: the row survives with {@code unlinkedAt} valued, so
 * the attachments of a claim remain auditable and the active set is served by the partial index
 * {@code idx_claim_documents_active}.
 *
 * @param claimId claim side of the relation
 * @param documentId document-service identifier
 * @param linkedAt attachment timestamp
 * @param linkedByActorId actor who attached the document, may be {@code null} (callers that assert
 *     no actor yet, ADR-0005)
 * @param unlinkedAt detach timestamp, {@code null} while the document is attached
 */
public record ClaimDocumentLink(
    UUID claimId,
    UUID documentId,
    OffsetDateTime linkedAt,
    UUID linkedByActorId,
    OffsetDateTime unlinkedAt) {

  /** Compact constructor: enforces the invariants the database also enforces. */
  public ClaimDocumentLink {
    Objects.requireNonNull(claimId, "claimId is required");
    Objects.requireNonNull(documentId, "documentId is required");
    Objects.requireNonNull(linkedAt, "linkedAt is required");
  }

  /**
   * @return {@code true} while the document is attached to the claim
   */
  public boolean isActive() {
    return unlinkedAt == null;
  }
}
