package it.enterprise.claims.documents.application.dto;

import it.enterprise.claims.documents.domain.model.Document;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API representation of a document (blueprint §7.2).
 *
 * <p>{@code storageKey} is deliberately not exposed: how and where the content is stored is an
 * internal detail, the caller addresses the content through {@code GET /{id}/content}. {@code
 * keyRef} <em>is</em> exposed because it is an opaque, non-secret alias of the encryption key (KMS
 * alias/version) that the caller may need for its own compliance records.
 *
 * @param id identifier
 * @param documentKey logical identity shared by all versions of this document
 * @param claimId claim the document belongs to
 * @param fileName original file name
 * @param contentType MIME type recorded at upload
 * @param sizeBytes size in bytes
 * @param documentType business classification
 * @param checksumSha256 hex SHA-256, lets the caller verify integrity
 * @param versionNumber business version, {@code 1..N}
 * @param isCurrent {@code true} for the live version of the document
 * @param keyRef opaque KMS key reference, may be {@code null}
 * @param retentionUntil end of the mandatory retention period, may be {@code null}
 * @param legalHold when {@code true} the document cannot be deleted
 * @param uploadedAt upload timestamp
 * @param version optimistic-locking version (incremented on update)
 */
public record DocumentResponse(
    UUID id,
    UUID documentKey,
    UUID claimId,
    String fileName,
    String contentType,
    Long sizeBytes,
    String documentType,
    String checksumSha256,
    Integer versionNumber,
    boolean isCurrent,
    String keyRef,
    LocalDate retentionUntil,
    boolean legalHold,
    OffsetDateTime uploadedAt,
    Integer version) {

  /**
   * @param document domain metadata to expose
   * @return the API representation
   */
  public static DocumentResponse from(Document document) {
    return new DocumentResponse(
        document.id(),
        document.documentKey(),
        document.claimId(),
        document.fileName(),
        document.contentType(),
        document.sizeBytes(),
        document.documentType(),
        document.checksumSha256(),
        document.versionNumber(),
        document.isCurrent(),
        document.keyRef(),
        document.retentionUntil(),
        document.legalHold(),
        document.uploadedAt(),
        document.version());
  }
}
