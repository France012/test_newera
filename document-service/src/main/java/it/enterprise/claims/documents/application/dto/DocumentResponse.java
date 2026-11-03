package it.enterprise.claims.documents.application.dto;

import it.enterprise.claims.documents.domain.model.Document;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API representation of a document (blueprint §2.4).
 *
 * <p>{@code storageKey} is deliberately not exposed: how and where the content is stored is an
 * internal detail, the caller addresses the content through {@code GET /{id}/content}.
 *
 * @param id identifier
 * @param claimId claim the document belongs to
 * @param fileName original file name
 * @param contentType MIME type recorded at upload
 * @param sizeBytes size in bytes
 * @param documentType business classification
 * @param checksumSha256 hex SHA-256, lets the caller verify integrity
 * @param uploadedAt upload timestamp
 * @param version optimistic-locking version (incremented on update)
 */
public record DocumentResponse(
    UUID id,
    UUID claimId,
    String fileName,
    String contentType,
    Long sizeBytes,
    String documentType,
    String checksumSha256,
    OffsetDateTime uploadedAt,
    Integer version) {

  /**
   * @param document domain metadata to expose
   * @return the API representation
   */
  public static DocumentResponse from(Document document) {
    return new DocumentResponse(
        document.id(),
        document.claimId(),
        document.fileName(),
        document.contentType(),
        document.sizeBytes(),
        document.documentType(),
        document.checksumSha256(),
        document.uploadedAt(),
        document.version());
  }
}
