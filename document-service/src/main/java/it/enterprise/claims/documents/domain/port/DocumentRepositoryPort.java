package it.enterprise.claims.documents.domain.port;

import it.enterprise.claims.documents.domain.model.Document;
import it.enterprise.claims.documents.domain.model.DocumentVersion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Outbound port for document metadata persistence.
 *
 * <p>Deletion is logical: every read method of the adapter filters on {@code deleted_at is null},
 * so a soft-deleted document can never be served again while staying auditable.
 *
 * <p>Versioning (decision A6): a document is a chain of rows sharing the same {@code documentKey};
 * {@link #saveVersion(Document)} is responsible for demoting the previous current row and for
 * enforcing "exactly one current version per key".
 */
public interface DocumentRepositoryPort {

  /**
   * @param document metadata of the first version of a new document
   * @return the persisted metadata, including the version assigned by Hibernate
   */
  Document save(Document document);

  /**
   * Inserts a new version of an existing {@code documentKey}, demoting the current one.
   *
   * @param document metadata of the new version
   * @return the persisted metadata
   */
  Document saveVersion(Document document);

  /**
   * @param documentId identifier to look up
   * @return the metadata, or empty when it does not exist or has been deleted
   */
  Optional<Document> findById(UUID documentId);

  /**
   * @param claimId claim whose documents are requested
   * @param currentOnly {@code true} to return only the live current version of each document
   * @param pageable page request
   * @return the matching page of live documents
   */
  Page<Document> findByClaimId(UUID claimId, boolean currentOnly, Pageable pageable);

  /**
   * @param documentKey logical document identity
   * @return every version of the document, newest first, deleted ones included
   */
  List<DocumentVersion> findVersions(UUID documentKey);

  /**
   * Applies the retention policy to a document.
   *
   * @param documentId identifier of the document
   * @param retentionUntil new end of the retention period, may be {@code null}
   * @param legalHold new legal-hold flag
   * @return the updated metadata
   */
  Document updateRetention(UUID documentId, LocalDate retentionUntil, boolean legalHold);

  /**
   * Marks the document as deleted (soft delete).
   *
   * @param documentId identifier of the document to delete
   * @return the deleted metadata
   */
  Document softDelete(UUID documentId);

  /**
   * @param documentKey logical document identity
   * @return the next business version number ({@code max + 1}, or {@code 1} when there is none)
   */
  int nextVersionNumber(UUID documentKey);
}
