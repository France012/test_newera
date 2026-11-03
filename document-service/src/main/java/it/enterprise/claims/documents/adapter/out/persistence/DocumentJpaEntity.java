package it.enterprise.claims.documents.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code documents.documents} (migration {@code V1__init_documents.sql}).
 *
 * <p>Only metadata is persisted: the binary content is written through {@code DocumentStoragePort}
 * and addressed by {@link #storageKey}. Deletion is logical ({@code deleted_at}), so every read
 * path must filter on {@code deleted_at is null}.
 *
 * <p>{@code claimId} is a logical reference to core-app (database-per-service: no cross-service
 * FK).
 */
@Entity
@Table(
    name = "documents",
    schema = "documents",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_documents_storage_key", columnNames = "storage_key"))
public class DocumentJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "content_type", nullable = false, length = 128)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private Long sizeBytes;

  @Column(name = "document_type", nullable = false, length = 32)
  private String documentType;

  @Column(name = "checksum_sha256", nullable = false, length = 64)
  private String checksumSha256;

  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;

  @Version
  @Column(name = "version", nullable = false)
  private Integer version;

  @Column(name = "uploaded_at", nullable = false, updatable = false)
  private OffsetDateTime uploadedAt;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

  /** Required by JPA. */
  protected DocumentJpaEntity() {}

  public DocumentJpaEntity(
      UUID id,
      UUID claimId,
      String fileName,
      String contentType,
      Long sizeBytes,
      String documentType,
      String checksumSha256,
      String storageKey) {
    this.id = id;
    this.claimId = claimId;
    this.fileName = fileName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.documentType = documentType;
    this.checksumSha256 = checksumSha256;
    this.storageKey = storageKey;
  }

  @PrePersist
  void onCreate() {
    if (uploadedAt == null) {
      uploadedAt = OffsetDateTime.now();
    }
  }

  /**
   * Marks the row as logically deleted.
   *
   * @param when deletion timestamp, {@code null} means "now"
   */
  public void markDeleted(OffsetDateTime when) {
    this.deletedAt = when == null ? OffsetDateTime.now() : when;
  }

  /**
   * @return {@code true} if the document has been logically deleted
   */
  public boolean isDeleted() {
    return deletedAt != null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getClaimId() {
    return claimId;
  }

  public void setClaimId(UUID claimId) {
    this.claimId = claimId;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getDocumentType() {
    return documentType;
  }

  public void setDocumentType(String documentType) {
    this.documentType = documentType;
  }

  public String getChecksumSha256() {
    return checksumSha256;
  }

  public void setChecksumSha256(String checksumSha256) {
    this.checksumSha256 = checksumSha256;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public void setStorageKey(String storageKey) {
    this.storageKey = storageKey;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }

  public OffsetDateTime getUploadedAt() {
    return uploadedAt;
  }

  public void setUploadedAt(OffsetDateTime uploadedAt) {
    this.uploadedAt = uploadedAt;
  }

  public OffsetDateTime getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(OffsetDateTime deletedAt) {
    this.deletedAt = deletedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DocumentJpaEntity that)) {
      return false;
    }
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    return "DocumentJpaEntity{id=" + id + ", claimId=" + claimId + ", fileName='" + fileName + "'}";
  }
}
