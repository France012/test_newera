package it.enterprise.claims.documents.application.dto;

import it.enterprise.claims.documents.domain.model.DocumentVersion;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API representation of one version of a document (blueprint §7.2).
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
public record DocumentVersionResponse(
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

  /**
   * @param version domain version summary to expose
   * @return the API representation
   */
  public static DocumentVersionResponse from(DocumentVersion version) {
    return new DocumentVersionResponse(
        version.id(),
        version.documentKey(),
        version.versionNumber(),
        version.isCurrent(),
        version.fileName(),
        version.documentType(),
        version.sizeBytes(),
        version.checksumSha256(),
        version.uploadedAt(),
        version.deletedAt());
  }
}
