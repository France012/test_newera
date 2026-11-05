package it.enterprise.claims.documents.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code documents.documents} (migrations {@code V1__init_documents.sql} and {@code
 * V2__document_versioning_retention.sql}).
 *
 * <p>Only metadata is persisted: the binary content is written through {@code DocumentStoragePort}
 * and addressed by {@link #storageKey}. Deletion is logical ({@code deleted_at}), so every read
 * path must filter on {@code deleted_at is null}.
 *
 * <p>{@code claimId} is a logical reference to core-app (database-per-service: no cross-service
 * FK).
 *
 * <p>Document versioning (decision A6): {@link #documentKey} is the logical identity shared by all
 * versions of a document, {@link #versionNumber} is the business version (1..N) and {@link
 * #current} marks the live current version — exactly one per {@code document_key}, enforced by the
 * partial unique index {@code uk_documents_current_version}. {@link #version} is
 * <strong>not</strong> the business version: it stays the Hibernate {@code @Version}
 * optimistic-locking counter.
 */
@Entity
@Table(
    name = "documents",
    schema = "documents",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_documents_storage_key", columnNames = "storage_key"),
      @UniqueConstraint(
          name = "uk_documents_document_key_version",
          columnNames = {"document_key", "version_number"})
    })
public class DocumentJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "claim_id", nullable = false, updatable = false)
  private UUID claimId;

  /** Logical identity of the document, shared by all its versions (V2). */
  @Column(name = "document_key", nullable = false, updatable = false)
  private UUID documentKey;

  /** Business version number inside the {@link #documentKey} chain (V2). */
  @Column(name = "version_number", nullable = false)
  private Integer versionNumber = 1;

  /** {@code true} for the single live current version of a {@link #documentKey} (V2). */
  @Column(name = "is_current", nullable = false)
  private boolean current = true;

  /** Previous version of the same document: linear chain, no forks (V2). */
  @Column(name = "supersedes_id", updatable = false)
  private UUID supersedesId;

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

  /**
   * Opaque encryption-key reference (KMS alias/version). Never a secret, never exposed as a key.
   */
  @Column(name = "key_ref", length = 160)
  private String keyRef;

  /** End of the mandatory retention period (V2). */
  @Column(name = "retention_until")
  private LocalDate retentionUntil;

  /** Legal hold: blocks the purge job and the soft delete regardless of {@link #retentionUntil}. */
  @Column(name = "legal_hold", nullable = false)
  private boolean legalHold;

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
    this(
        id,
        id,
        claimId,
        fileName,
        contentType,
        sizeBytes,
        documentType,
        checksumSha256,
        storageKey);
  }

  public DocumentJpaEntity(
      UUID id,
      UUID documentKey,
      UUID claimId,
      String fileName,
      String contentType,
      Long sizeBytes,
      String documentType,
      String checksumSha256,
      String storageKey) {
    this.id = id;
    this.documentKey = documentKey;
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
    if (documentKey == null) {
      documentKey = id;
    }
    if (versionNumber == null) {
      versionNumber = 1;
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

  /**
   * Demotes this row to a non-current version (a newer version of the same {@code document_key}
   * exists).
   */
  public void markSuperseded() {
    this.current = false;
  }

  /**
   * Promotes this row to the current version of its {@code document_key}.
   *
   * <p>The partial unique index {@code uk_documents_current_version} makes this safe only if no
   * other live current row exists for the same {@code document_key}.
   */
  public void markCurrent() {
    this.current = true;
  }

  /**
   * Applies the retention policy.
   *
   * @param retentionUntil end of the retention period, {@code null} to clear it
   * @param legalHold whether the document is on legal hold
   */
  public void updateRetention(LocalDate retentionUntil, boolean legalHold) {
    this.retentionUntil = retentionUntil;
    this.legalHold = legalHold;
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

  public UUID getDocumentKey() {
    return documentKey;
  }

  public void setDocumentKey(UUID documentKey) {
    this.documentKey = documentKey;
  }

  public Integer getVersionNumber() {
    return versionNumber;
  }

  public void setVersionNumber(Integer versionNumber) {
    this.versionNumber = versionNumber;
  }

  public boolean isCurrent() {
    return current;
  }

  public void setCurrent(boolean current) {
    this.current = current;
  }

  public UUID getSupersedesId() {
    return supersedesId;
  }

  public void setSupersedesId(UUID supersedesId) {
    this.supersedesId = supersedesId;
  }

  public String getKeyRef() {
    return keyRef;
  }

  public void setKeyRef(String keyRef) {
    this.keyRef = keyRef;
  }

  public LocalDate getRetentionUntil() {
    return retentionUntil;
  }

  public void setRetentionUntil(LocalDate retentionUntil) {
    this.retentionUntil = retentionUntil;
  }

  public boolean isLegalHold() {
    return legalHold;
  }

  public void setLegalHold(boolean legalHold) {
    this.legalHold = legalHold;
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
