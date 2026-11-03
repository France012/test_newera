package it.enterprise.claims.documents.domain.port;

import it.enterprise.claims.documents.domain.model.Document;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Outbound port for document metadata persistence.
 *
 * <p>Deletion is logical: every read method of the adapter filters on {@code deleted_at is null},
 * so a soft-deleted document can never be served again while staying auditable.
 */
public interface DocumentRepositoryPort {

  /**
   * @param document metadata to insert
   * @return the persisted metadata, including the version assigned by Hibernate
   */
  Document save(Document document);

  /**
   * @param documentId identifier to look up
   * @return the metadata, or empty when it does not exist or has been deleted
   */
  Optional<Document> findById(UUID documentId);

  /**
   * @param claimId claim whose documents are requested
   * @param pageable page request
   * @return the matching page of live documents
   */
  Page<Document> findByClaimId(UUID claimId, Pageable pageable);

  /**
   * Marks the document as deleted (soft delete).
   *
   * @param documentId identifier of the document to delete
   * @return the deleted metadata
   */
  Document softDelete(UUID documentId);
}
