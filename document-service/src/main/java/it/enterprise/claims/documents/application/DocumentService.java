package it.enterprise.claims.documents.application;

import it.enterprise.claims.documents.application.dto.DocumentContent;
import it.enterprise.claims.documents.application.dto.DocumentPageResponse;
import it.enterprise.claims.documents.application.dto.DocumentResponse;
import it.enterprise.claims.documents.application.dto.DocumentRetentionUpdateRequest;
import it.enterprise.claims.documents.application.dto.DocumentVersionResponse;
import it.enterprise.claims.documents.application.exception.DocumentNotFoundException;
import it.enterprise.claims.documents.application.exception.DocumentOnLegalHoldException;
import it.enterprise.claims.documents.application.exception.InvalidDocumentException;
import it.enterprise.claims.documents.application.exception.RetentionViolationException;
import it.enterprise.claims.documents.application.exception.StorageException;
import it.enterprise.claims.documents.domain.model.Document;
import it.enterprise.claims.documents.domain.port.DocumentRepositoryPort;
import it.enterprise.claims.documents.domain.port.DocumentStoragePort;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Use cases of the document aggregate: upload, versioning, retention, read metadata, stream content,
 * list by claim and soft delete.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>the document id is generated before the content is stored, because the storage key is
 *       derived from it (no user-supplied name ever reaches the filesystem);
 *   <li>the SHA-256 checksum is computed on the bytes that are actually stored, so it can be used
 *       to verify integrity and to deduplicate later without a schema change;
 *   <li>a new version keeps the {@code documentKey} of the first one, gets the next
 *       {@code versionNumber} and becomes the only current row of the chain (decision A6);
 *   <li>deletion is logical and is forbidden while the document is on legal hold (decision A7):
 *       metadata stays auditable and the content is kept for the retention period (physical purge
 *       is a separate retention job, out of scope in phase 1).
 * </ul>
 */
@Service
public class DocumentService {

  private static final String SHA_256 = "SHA-256";
  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
  private static final String DEFAULT_DOCUMENT_TYPE = "OTHER";
  private static final String FALLBACK_FILE_NAME = "document";
  private static final int MAX_FILE_NAME_LENGTH = 255;
  private static final int MAX_CONTENT_TYPE_LENGTH = 128;
  private static final int MAX_DOCUMENT_TYPE_LENGTH = 32;

  private final DocumentRepositoryPort documentRepository;
  private final DocumentStoragePort documentStorage;
  private final Clock clock;

  /**
   * @param documentRepository metadata persistence port
   * @param documentStorage content storage port
   * @param clock clock used for the upload timestamp and the retention validation (injectable in
   *     tests)
   */
  public DocumentService(
      DocumentRepositoryPort documentRepository, DocumentStoragePort documentStorage, Clock clock) {
    this.documentRepository = documentRepository;
    this.documentStorage = documentStorage;
    this.clock = clock;
  }

  /**
   * Stores the first version of a new document: content first, metadata right after.
   *
   * @param file uploaded multipart file, must not be empty
   * @param claimId claim the document belongs to
   * @param documentType business classification, defaults to {@code OTHER}
   * @return the persisted metadata
   * @throws InvalidDocumentException when the file is empty or an argument is too long
   */
  @Transactional
  public DocumentResponse upload(MultipartFile file, UUID claimId, String documentType) {
    if (file == null || file.isEmpty()) {
      throw new InvalidDocumentException("file must be provided and must not be empty");
    }
    if (claimId == null) {
      throw new InvalidDocumentException("claimId is required");
    }

    byte[] content = readContent(file);
    UUID documentId = UUID.randomUUID();
    String storageKey = documentStorage.store(claimId, documentId, content);

    Document document =
        new Document(
            documentId,
            claimId,
            sanitizeFileName(file.getOriginalFilename()),
            normalizeContentType(file.getContentType()),
            (long) content.length,
            normalizeDocumentType(documentType),
            sha256(content),
            storageKey,
            OffsetDateTime.now(clock),
            1);

    return DocumentResponse.from(documentRepository.save(document));
  }

  /**
   * Stores a new version of an existing document: the previous current version is demoted and the
   * new row becomes the current one.
   *
   * @param documentId any version of the document (the {@code documentKey} is what matters)
   * @param file new content, must not be empty
   * @param documentType business classification, defaults to the one of the previous version
   * @return the persisted metadata of the new version
   * @throws DocumentNotFoundException when the document does not exist or was deleted
   * @throws InvalidDocumentException when the file is empty
   */
  @Transactional
  public DocumentResponse uploadNewVersion(
      UUID documentId, MultipartFile file, String documentType) {
    if (file == null || file.isEmpty()) {
      throw new InvalidDocumentException("file must be provided and must not be empty");
    }
    Document previous = load(documentId);

    byte[] content = readContent(file);
    UUID newVersionId = UUID.randomUUID();
    String storageKey = documentStorage.store(previous.claimId(), newVersionId, content);
    int versionNumber = documentRepository.nextVersionNumber(previous.documentKey());

    Document nextVersion =
        new Document(
            newVersionId,
            previous.documentKey(),
            previous.claimId(),
            sanitizeFileName(file.getOriginalFilename()),
            normalizeContentType(file.getContentType()),
            (long) content.length,
            documentType == null || documentType.isBlank()
                ? previous.documentType()
                : normalizeDocumentType(documentType),
            sha256(content),
            storageKey,
            versionNumber,
            true,
            previous.keyRef(),
            previous.retentionUntil(),
            previous.legalHold(),
            OffsetDateTime.now(clock),
            null);

    return DocumentResponse.from(documentRepository.saveVersion(nextVersion));
  }

  /**
   * @param documentId identifier to read
   * @return the metadata
   * @throws DocumentNotFoundException when the document does not exist or was deleted
   */
  @Transactional(readOnly = true)
  public DocumentResponse findById(UUID documentId) {
    return DocumentResponse.from(load(documentId));
  }

  /**
   * @param documentId identifier of the document to stream
   * @return metadata plus raw bytes
   * @throws DocumentNotFoundException when the document does not exist or was deleted
   */
  @Transactional(readOnly = true)
  public DocumentContent download(UUID documentId) {
    Document document = load(documentId);
    return new DocumentContent(
        DocumentResponse.from(document), documentStorage.load(document.storageKey()));
  }

  /**
   * @param claimId claim whose documents are requested
   * @param currentOnly {@code true} (API default) to return only the live current version of each
   *     document
   * @param pageable page request
   * @return the live documents of the claim
   */
  @Transactional(readOnly = true)
  public DocumentPageResponse findByClaimId(UUID claimId, boolean currentOnly, Pageable pageable) {
    if (claimId == null) {
      throw new InvalidDocumentException("claimId is required");
    }
    return DocumentPageResponse.from(documentRepository.findByClaimId(claimId, currentOnly, pageable));
  }

  /**
   * @param documentId any version of the document
   * @return the whole version chain, newest first
   * @throws DocumentNotFoundException when the document does not exist or was deleted
   */
  @Transactional(readOnly = true)
  public List<DocumentVersionResponse> findVersions(UUID documentId) {
    Document document = load(documentId);
    return documentRepository.findVersions(document.documentKey()).stream()
        .map(DocumentVersionResponse::from)
        .toList();
  }

  /**
   * Applies the retention policy of a document.
   *
   * @param documentId identifier of the document
   * @param request new retention date and/or legal-hold flag ({@code null} = leave untouched)
   * @return the updated metadata
   * @throws DocumentNotFoundException when the document does not exist or was deleted
   * @throws RetentionViolationException when the date is in the past (HTTP 400)
   */
  @Transactional
  public DocumentResponse updateRetention(
      UUID documentId, DocumentRetentionUpdateRequest request) {
    Document document = load(documentId);
    LocalDate retentionUntil =
        request.retentionUntil() == null ? document.retentionUntil() : request.retentionUntil();
    boolean legalHold = request.legalHold() == null ? document.legalHold() : request.legalHold();

    if (retentionUntil != null && retentionUntil.isBefore(LocalDate.now(clock))) {
      throw new RetentionViolationException(
          "retentionUntil " + retentionUntil + " is in the past");
    }
    return DocumentResponse.from(
        documentRepository.updateRetention(documentId, retentionUntil, legalHold));
  }

  /**
   * Soft delete: the metadata is kept for auditing, reads stop returning the document.
   *
   * @param documentId identifier of the document to delete
   * @throws DocumentNotFoundException when the document does not exist or was already deleted
   * @throws DocumentOnLegalHoldException when the document is on legal hold (HTTP 409)
   */
  @Transactional
  public void delete(UUID documentId) {
    Document document = load(documentId);
    if (document.legalHold()) {
      throw new DocumentOnLegalHoldException(documentId);
    }
    documentRepository.softDelete(documentId);
  }

  private Document load(UUID documentId) {
    return documentRepository
        .findById(documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
  }

  private static byte[] readContent(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException ex) {
      throw new StorageException("Unable to read the uploaded file", ex);
    }
  }

  /**
   * Reduces any client-supplied name to a basename inside the 255-char column, so an upload can
   * never influence the storage path (the key is built from the generated ids only).
   *
   * @param originalFileName name sent by the client, may be {@code null}
   * @return a non-blank basename, at most {@value #MAX_FILE_NAME_LENGTH} characters
   */
  private static String sanitizeFileName(String originalFileName) {
    if (originalFileName == null || originalFileName.isBlank()) {
      return FALLBACK_FILE_NAME;
    }
    String basename = originalFileName.replace('\\', '/');
    int lastSlash = basename.lastIndexOf('/');
    if (lastSlash >= 0) {
      basename = basename.substring(lastSlash + 1);
    }
    basename = basename.replaceAll("[\\p{Cntrl}]", "").trim();
    if (basename.isBlank()) {
      return FALLBACK_FILE_NAME;
    }
    return basename.length() > MAX_FILE_NAME_LENGTH
        ? basename.substring(0, MAX_FILE_NAME_LENGTH)
        : basename;
  }

  private static String normalizeContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return DEFAULT_CONTENT_TYPE;
    }
    return contentType.length() > MAX_CONTENT_TYPE_LENGTH
        ? DEFAULT_CONTENT_TYPE
        : contentType.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeDocumentType(String documentType) {
    if (documentType == null || documentType.isBlank()) {
      return DEFAULT_DOCUMENT_TYPE;
    }
    String candidate = documentType.trim().toUpperCase(Locale.ROOT);
    if (candidate.length() > MAX_DOCUMENT_TYPE_LENGTH) {
      throw new InvalidDocumentException(
          "documentType must be at most " + MAX_DOCUMENT_TYPE_LENGTH + " characters");
    }
    return candidate;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(content));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(SHA_256 + " is not available in this JVM", ex);
    }
  }
}
