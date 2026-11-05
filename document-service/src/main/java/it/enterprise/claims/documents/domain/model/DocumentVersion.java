package it.enterprise.claims.documents.domain.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight summary of one version of a document, used by the history endpoint ({@code GET
 * /api/v1/documents/{documentId}/versions}).
 *
 * <p>Deliberately not a {@link Document}: the history needs to show the versions that have been
 * superseded and the ones that have been logically deleted, while the rest of the application only
 * ever works on the live current version.
 *
 * @param id identifier of the version row
 * @param documentKey logical identity shared by all versions
 * @param versionNumber business version, {@code 1..N}
 * @param isCurrent {@code true} for the live version
 * @param fileName original file name of that version
 * @param documentType business classification of that version
 * @param sizeBytes size in bytes
 * @param checksumSha256 hex SHA-256 of the content of that version
 * @param uploadedAt upload timestamp of that version
 * @param deletedAt soft-delete timestamp, {@code null} while the version is not deleted
 */
public record DocumentVersion(
    UUID id,
    UUID documentKey,
    Integer versionNumber,
    boolean isCurrent,
    String fileName,
    String documentType,
    Long sizeBytes,
    String checksumSha256,
    OffsetDateTime uploadedAt,
    OffsetDateTime deletedAt) {

  /** Compact constructor enforcing the invariants the database also enforces. */
  public DocumentVersion {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(documentKey, "documentKey is required");
    Objects.requireNonNull(versionNumber, "versionNumber is required");
    Objects.requireNonNull(fileName, "fileName is required");
    Objects.requireNonNull(documentType, "documentType is required");
    Objects.requireNonNull(sizeBytes, "sizeBytes is required");
    Objects.requireNonNull(checksumSha256, "checksumSha256 is required");
    Objects.requireNonNull(uploadedAt, "uploadedAt is required");
  }
}
