package it.enterprise.claims.documents.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata of an uploaded document ({@code documents.documents}).
 *
 * <p>The binary content is not part of the domain object: it is addressed by {@link #storageKey}
 * and read through {@code DocumentStoragePort}, so switching from the local filesystem to object
 * storage does not touch the domain or the API.
 *
 * @param id document identifier, generated in the application layer
 * @param claimId claim the document belongs to (logical reference to core-app, no cross-service FK)
 * @param fileName original file name, sanitised to a basename
 * @param contentType MIME type recorded at upload time
 * @param sizeBytes size of the stored content in bytes
 * @param documentType business classification (e.g. {@code INVOICE})
 * @param checksumSha256 hex SHA-256 of the stored content
 * @param storageKey key the content is addressed by
 * @param uploadedAt upload timestamp
 * @param version optimistic-locking counter managed by Hibernate
 */
public record Document(
    UUID id,
    UUID claimId,
    String fileName,
    String contentType,
    Long sizeBytes,
    String documentType,
    String checksumSha256,
    String storageKey,
    OffsetDateTime uploadedAt,
    Integer version) {

  /** Compact constructor enforcing the invariants the database also enforces. */
  public Document {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(claimId, "claimId is required");
    Objects.requireNonNull(fileName, "fileName is required");
    Objects.requireNonNull(contentType, "contentType is required");
    Objects.requireNonNull(sizeBytes, "sizeBytes is required");
    Objects.requireNonNull(documentType, "documentType is required");
    Objects.requireNonNull(checksumSha256, "checksumSha256 is required");
    Objects.requireNonNull(storageKey, "storageKey is required");
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
  }
}
