package it.enterprise.claims.documents.adapter.out.persistence;

import it.enterprise.claims.documents.application.exception.DocumentNotFoundException;
import it.enterprise.claims.documents.application.exception.DocumentVersionConflictException;
import it.enterprise.claims.documents.domain.model.Document;
import it.enterprise.claims.documents.domain.model.DocumentVersion;
import it.enterprise.claims.documents.domain.port.DocumentRepositoryPort;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter implementing {@link DocumentRepositoryPort} over the DBA-owned entity and
 * Spring Data repository.
 *
 * <p>Every read path goes through the {@code DeletedAtIsNull} query derivation of the repository,
 * so a soft-deleted document can never be served: the filter is not something a caller can forget.
 *
 * <p>Versioning (decision A6): {@link #saveVersion(Document)} demotes the current row before
 * inserting the new one, and translates a constraint violation of {@code
 * uk_documents_current_version} or {@code uk_documents_document_key_version} into a {@link
 * DocumentVersionConflictException}, so a lost race becomes a 409 instead of a corrupted chain.
 */
@Repository
public class DocumentRepositoryAdapter implements DocumentRepositoryPort {

  private final DocumentJpaRepository documentJpaRepository;

  /**
   * @param documentJpaRepository access to {@code documents.documents}
   */
  public DocumentRepositoryAdapter(DocumentJpaRepository documentJpaRepository) {
    this.documentJpaRepository = documentJpaRepository;
  }

  @Override
  @Transactional
  public Document save(Document document) {
    DocumentJpaEntity entity = newEntity(document);
    entity.setVersionNumber(document.versionNumber());
    entity.setCurrent(document.isCurrent());
    return toDomain(documentJpaRepository.saveAndFlush(entity));
  }

  @Override
  @Transactional
  public Document saveVersion(Document document) {
    Optional<DocumentJpaEntity> current =
        documentJpaRepository.findByDocumentKeyAndCurrentTrueAndDeletedAtIsNull(
            document.documentKey());
    current.ifPresent(
        previous -> {
          previous.markSuperseded();
          documentJpaRepository.saveAndFlush(previous);
        });

    DocumentJpaEntity entity = newEntity(document);
    entity.setVersionNumber(document.versionNumber());
    entity.setCurrent(true);
    entity.setSupersedesId(current.map(DocumentJpaEntity::getId).orElse(null));
    try {
      return toDomain(documentJpaRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException ex) {
      throw new DocumentVersionConflictException(document.documentKey(), ex);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Document> findById(UUID documentId) {
    return documentJpaRepository
        .findByIdAndDeletedAtIsNull(documentId)
        .map(DocumentRepositoryAdapter::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Document> findByClaimId(UUID claimId, boolean currentOnly, Pageable pageable) {
    Page<DocumentJpaEntity> page =
        currentOnly
            ? documentJpaRepository.findByClaimIdAndCurrentTrueAndDeletedAtIsNull(
                claimId, pageable)
            : documentJpaRepository.findByClaimIdAndDeletedAtIsNull(claimId, pageable);
    return page.map(DocumentRepositoryAdapter::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DocumentVersion> findVersions(UUID documentKey) {
    return documentJpaRepository
        .findByDocumentKeyAndDeletedAtIsNullOrderByVersionNumberDesc(documentKey)
        .stream()
        .map(DocumentRepositoryAdapter::toVersion)
        .toList();
  }

  @Override
  @Transactional
  public Document updateRetention(UUID documentId, LocalDate retentionUntil, boolean legalHold) {
    DocumentJpaEntity entity =
        documentJpaRepository
            .findByIdAndDeletedAtIsNull(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
    entity.updateRetention(retentionUntil, legalHold);
    return toDomain(documentJpaRepository.saveAndFlush(entity));
  }

  @Override
  @Transactional
  public Document softDelete(UUID documentId) {
    DocumentJpaEntity entity =
        documentJpaRepository
            .findByIdAndDeletedAtIsNull(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
    entity.markDeleted(OffsetDateTime.now());
    return toDomain(documentJpaRepository.saveAndFlush(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public int nextVersionNumber(UUID documentKey) {
    return documentJpaRepository.findMaxVersionNumber(documentKey) + 1;
  }

  private static DocumentJpaEntity newEntity(Document document) {
    DocumentJpaEntity entity =
        new DocumentJpaEntity(
            document.id(),
            document.documentKey(),
            document.claimId(),
            document.fileName(),
            document.contentType(),
            document.sizeBytes(),
            document.documentType(),
            document.checksumSha256(),
            document.storageKey());
    entity.setUploadedAt(document.uploadedAt());
    entity.setKeyRef(document.keyRef());
    entity.setRetentionUntil(document.retentionUntil());
    entity.setLegalHold(document.legalHold());
    return entity;
  }

  private static Document toDomain(DocumentJpaEntity entity) {
    return new Document(
        entity.getId(),
        entity.getDocumentKey(),
        entity.getClaimId(),
        entity.getFileName(),
        entity.getContentType(),
        entity.getSizeBytes(),
        entity.getDocumentType(),
        entity.getChecksumSha256(),
        entity.getStorageKey(),
        entity.getVersionNumber(),
        entity.isCurrent(),
        entity.getKeyRef(),
        entity.getRetentionUntil(),
        entity.isLegalHold(),
        entity.getUploadedAt(),
        entity.getVersion());
  }

  private static DocumentVersion toVersion(DocumentJpaEntity entity) {
    return new DocumentVersion(
        entity.getId(),
        entity.getDocumentKey(),
        entity.getVersionNumber(),
        entity.isCurrent(),
        entity.getFileName(),
        entity.getDocumentType(),
        entity.getSizeBytes(),
        entity.getChecksumSha256(),
        entity.getUploadedAt(),
        entity.getDeletedAt());
  }
}
