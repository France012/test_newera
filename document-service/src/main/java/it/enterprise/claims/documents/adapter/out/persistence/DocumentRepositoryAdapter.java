package it.enterprise.claims.documents.adapter.out.persistence;

import it.enterprise.claims.documents.application.exception.DocumentNotFoundException;
import it.enterprise.claims.documents.domain.model.Document;
import it.enterprise.claims.documents.domain.port.DocumentRepositoryPort;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
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
    DocumentJpaEntity entity =
        new DocumentJpaEntity(
            document.id(),
            document.claimId(),
            document.fileName(),
            document.contentType(),
            document.sizeBytes(),
            document.documentType(),
            document.checksumSha256(),
            document.storageKey());
    entity.setUploadedAt(document.uploadedAt());
    return toDomain(documentJpaRepository.saveAndFlush(entity));
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
  public Page<Document> findByClaimId(UUID claimId, Pageable pageable) {
    return documentJpaRepository
        .findByClaimIdAndDeletedAtIsNull(claimId, pageable)
        .map(DocumentRepositoryAdapter::toDomain);
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

  private static Document toDomain(DocumentJpaEntity entity) {
    return new Document(
        entity.getId(),
        entity.getClaimId(),
        entity.getFileName(),
        entity.getContentType(),
        entity.getSizeBytes(),
        entity.getDocumentType(),
        entity.getChecksumSha256(),
        entity.getStorageKey(),
        entity.getUploadedAt(),
        entity.getVersion());
  }
}
