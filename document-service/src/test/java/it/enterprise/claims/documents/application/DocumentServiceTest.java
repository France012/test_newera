package it.enterprise.claims.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.enterprise.claims.documents.application.dto.DocumentContent;
import it.enterprise.claims.documents.application.dto.DocumentResponse;
import it.enterprise.claims.documents.application.exception.DocumentNotFoundException;
import it.enterprise.claims.documents.application.exception.InvalidDocumentException;
import it.enterprise.claims.documents.domain.model.Document;
import it.enterprise.claims.documents.domain.port.DocumentRepositoryPort;
import it.enterprise.claims.documents.domain.port.DocumentStoragePort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;

/** Unit tests of the document use cases: sanitisation, checksum, storage contract, soft delete. */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2025-03-10T09:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime UPLOADED_AT = OffsetDateTime.parse("2025-03-10T09:00:00Z");
  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String STORAGE_KEY = CLAIM_ID + "/" + DOCUMENT_ID;

  /** SHA-256 of the ASCII string "hello". */
  private static final String HELLO_SHA256 =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  @Mock private DocumentRepositoryPort documentRepository;
  @Mock private DocumentStoragePort documentStorage;

  private DocumentService documentService;

  @BeforeEach
  void setUp() {
    documentService = new DocumentService(documentRepository, documentStorage, FIXED_CLOCK);
  }

  @Test
  @DisplayName("upload() stores the content, computes the checksum and keeps only a basename")
  void uploadStoresContentAndSanitisesFileName() {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file =
        new MockMultipartFile("file", "../../etc/passwd", "text/plain", content);
    when(documentStorage.store(eq(CLAIM_ID), any(UUID.class), eq(content))).thenReturn(STORAGE_KEY);
    when(documentRepository.save(any(Document.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    DocumentResponse response = documentService.upload(file, CLAIM_ID, "invoice");

    assertThat(response.fileName()).isEqualTo("passwd");
    assertThat(response.contentType()).isEqualTo("text/plain");
    assertThat(response.sizeBytes()).isEqualTo(content.length);
    assertThat(response.checksumSha256()).isEqualTo(HELLO_SHA256);
    assertThat(response.documentType()).isEqualTo("INVOICE");
    assertThat(response.uploadedAt()).isEqualTo(UPLOADED_AT);

    verify(documentStorage).store(eq(CLAIM_ID), any(UUID.class), eq(content));
  }

  @Test
  @DisplayName("upload() rejects an empty file without touching storage")
  void uploadRejectsEmptyFile() {
    MockMultipartFile empty =
        new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

    assertThatThrownBy(() -> documentService.upload(empty, CLAIM_ID, "INVOICE"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("must not be empty");

    verify(documentStorage, never()).store(any(), any(), any());
  }

  @Test
  @DisplayName("upload() rejects a documentType longer than the column")
  void uploadRejectsTooLongDocumentType() {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "a.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> documentService.upload(file, CLAIM_ID, "X".repeat(33)))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("documentType");
  }

  @Test
  @DisplayName("download() returns metadata plus the bytes read through the storage port")
  void downloadReturnsContent() {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));
    when(documentStorage.load(STORAGE_KEY)).thenReturn(content);

    DocumentContent result = documentService.download(DOCUMENT_ID);

    assertThat(result.content()).isEqualTo(content);
    assertThat(result.metadata().checksumSha256()).isEqualTo(HELLO_SHA256);
  }

  @Test
  @DisplayName("download() on an unknown id is a not-found, not a storage error")
  void downloadRejectsUnknownDocument() {
    when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> documentService.download(DOCUMENT_ID))
        .isInstanceOf(DocumentNotFoundException.class);
    verify(documentStorage, never()).load(any());
  }

  @Test
  @DisplayName("delete() soft deletes through the repository")
  void deleteSoftDeletes() {
    when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));
    when(documentRepository.softDelete(DOCUMENT_ID)).thenReturn(document());

    documentService.delete(DOCUMENT_ID);

    verify(documentRepository).softDelete(DOCUMENT_ID);
  }

  @Test
  @DisplayName("delete() on an unknown id does not touch the repository")
  void deleteRejectsUnknownDocument() {
    when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> documentService.delete(DOCUMENT_ID))
        .isInstanceOf(DocumentNotFoundException.class);
    verify(documentRepository, never()).softDelete(any());
  }

  @Test
  @DisplayName("findByClaimId() requires a claim id")
  void findByClaimIdRequiresClaimId() {
    assertThatThrownBy(() -> documentService.findByClaimId(null, true, PageRequest.of(0, 20)))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("claimId");
  }

  private static Document document() {
    return new Document(
        DOCUMENT_ID,
        CLAIM_ID,
        "passwd",
        "text/plain",
        5L,
        "INVOICE",
        HELLO_SHA256,
        STORAGE_KEY,
        UPLOADED_AT,
        1);
  }
}
