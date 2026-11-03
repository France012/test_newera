package it.enterprise.claims.documents.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.documents.application.DocumentService;
import it.enterprise.claims.documents.application.dto.DocumentContent;
import it.enterprise.claims.documents.application.dto.DocumentPageResponse;
import it.enterprise.claims.documents.application.dto.DocumentResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Document endpoints, base path {@code /api/v1/documents} (blueprint §2.4).
 *
 * <p>No {@code produces} at class level: the content endpoint streams binary data, so each JSON
 * endpoint declares its media type explicitly.
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Upload, download and soft-delete of claim documents")
public class DocumentController {

  private static final int MAX_PAGE_SIZE = 100;

  private final DocumentService documentService;

  /**
   * @param documentService document use cases
   */
  public DocumentController(DocumentService documentService) {
    this.documentService = documentService;
  }

  /**
   * Uploads a document as {@code multipart/form-data}.
   *
   * @param file the file part, must not be empty
   * @param claimId claim the document belongs to
   * @param documentType business classification, defaults to {@code OTHER}
   * @return 201 with the stored metadata and its {@code Location}
   */
  @PostMapping(
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Upload a document for a claim")
  public ResponseEntity<DocumentResponse> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam UUID claimId,
      @RequestParam(required = false, defaultValue = "OTHER") String documentType) {
    DocumentResponse created = documentService.upload(file, claimId, documentType);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{documentId}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  /**
   * @param claimId claim whose documents are requested
   * @param page zero-based page index
   * @param size page size, clamped to 100
   * @return the live documents of the claim, newest first
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "List the live documents of a claim")
  public DocumentPageResponse list(
      @RequestParam UUID claimId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PageRequest pageRequest =
        PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "uploadedAt"));
    return documentService.findByClaimId(claimId, pageRequest);
  }

  /**
   * @param documentId identifier of the document
   * @return the document metadata
   */
  @GetMapping(value = "/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get document metadata")
  public DocumentResponse get(@PathVariable UUID documentId) {
    return documentService.findById(documentId);
  }

  /**
   * Streams the stored content.
   *
   * @param documentId identifier of the document
   * @return the raw bytes with {@code Content-Disposition: attachment}
   */
  @GetMapping("/{documentId}/content")
  @Operation(summary = "Download the document content")
  public ResponseEntity<Resource> content(@PathVariable UUID documentId) {
    DocumentContent documentContent = documentService.download(documentId);
    byte[] bytes = documentContent.content();
    String fileName = documentContent.metadata().fileName();
    return ResponseEntity.ok()
        .contentType(mediaType(documentContent.metadata().contentType()))
        .contentLength(bytes.length)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new ByteArrayResource(bytes));
  }

  /**
   * Soft deletes a document: it disappears from reads but stays auditable.
   *
   * @param documentId identifier of the document
   * @return 204
   */
  @DeleteMapping("/{documentId}")
  @Operation(summary = "Soft delete a document")
  public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
    documentService.delete(documentId);
    return ResponseEntity.noContent().build();
  }

  private static MediaType mediaType(String rawContentType) {
    try {
      return MediaType.parseMediaType(rawContentType);
    } catch (InvalidMediaTypeException ex) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
