package it.enterprise.claims.documents.domain.model;

import java.time.LocalDate;
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
 * <p>Versioning (decision A6): {@link #documentKey} is the logical identity shared by every version
 * of the same document, {@link #versionNumber} is the business version ({@code 1..N}) and {@link
 * #isCurrent} marks the live one. {@link #version} is <strong>not</strong> the business version: it
 * is the Hibernate optimistic-locking counter.
 *
 * @param id document identifier, generated in the application layer
 * @param documentKey logical identity shared by all versions of this document
 * @param claimId claim the document belongs to (logical reference to core-app, no cross-service FK)
 * @param fileName original file name, sanitised to a basename
 * @param contentType MIME type recorded at upload time
 * @param sizeBytes size of the stored content in bytes
 * @param documentType business classification (e.g. {@code INVOICE})
 * @param checksumSha256 hex SHA-256 of the stored content
 * @param storageKey key the content is addressed by (never exposed by the API)
 * @param versionNumber business version inside the {@code documentKey} chain, starts at 1
 * @param isCurrent {@code true} for the live version of the document
 * @param keyRef opaque KMS key reference (alias/version), never a secret, may be {@code null}
 * @param retentionUntil end of the mandatory retention period, may be {@code null}
 * @param legalHold when {@code true} the document cannot be deleted, whatever the retention says
 * @param uploadedAt upload timestamp
 * @param version optimistic-locking counter managed by Hibernate
 */
public record Document(
    UUID id,
    UUID documentKey,
    UUID claimId,
    String fileName,
    String contentType,
    Long sizeBytes,
    String documentType,
    String checksumSha256,
    String storageKey,
    Integer versionNumber,
    boolean isCurrent,
    String keyRef,
    LocalDate retentionUntil,
    boolean legalHold,
    OffsetDateTime uploadedAt,
    Integer version) {

  /**
   * Convenience constructor for the first version of a new document: {@code documentKey = id},
   * {@code versionNumber = 1}, current, no retention policy.
   *
   * @param id document identifier
   * @param claimId claim the document belongs to
   * @param fileName original file name
   * @param contentType MIME type
   * @param sizeBytes size in bytes
   * @param documentType business classification
   * @param checksumSha256 hex SHA-256 of the content
   * @param storageKey storage key
   * @param uploadedAt upload timestamp
   * @param version optimistic-locking counter
   */
  public Document(
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
    this(
        id,
        id,
        claimId,
        fileName,
        contentType,
        sizeBytes,
        documentType,
        checksumSha256,
        storageKey,
        1,
        true,
        null,
        null,
        false,
        uploadedAt,
        version);
  }

  /** Compact constructor enforcing the invariants the database also enforces. */
  public Document {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(documentKey, "documentKey is required");
    Objects.requireNonNull(claimId, "claimId is required");
    Objects.requireNonNull(fileName, "fileName is required");
    Objects.requireNonNull(contentType, "contentType is required");
    Objects.requireNonNull(sizeBytes, "sizeBytes is required");
    Objects.requireNonNull(documentType, "documentType is required");
    Objects.requireNonNull(checksumSha256, "checksumSha256 is required");
    Objects.requireNonNull(storageKey, "storageKey is required");
    Objects.requireNonNull(versionNumber, "versionNumber is required");
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative");
    }
    if (versionNumber < 1) {
      throw new IllegalArgumentException("versionNumber must be at least 1");
    }
  }

  /**
   * @return {@code true} when the retention period or the legal hold forbids the deletion
   */
  public boolean isDeletionBlocked() {
    return legalHold || retentionUntil != null;
  }

  /**
   * @param newRetentionUntil end of the retention period, may be {@code null} to clear it
   * @param newLegalHold whether the document is on legal hold
   * @return a copy of this document with the new retention policy
   */
  public Document withRetention(LocalDate newRetentionUntil, boolean newLegalHold) {
    return new Document(
        id,
        documentKey,
        claimId,
        fileName,
        contentType,
        sizeBytes,
        documentType,
        checksumSha256,
        storageKey,
        versionNumber,
        isCurrent,
        keyRef,
        newRetentionUntil,
        newLegalHold,
        uploadedAt,
        version);
  }
}
