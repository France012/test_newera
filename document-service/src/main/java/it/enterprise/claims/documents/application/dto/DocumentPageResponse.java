package it.enterprise.claims.documents.application.dto;

import it.enterprise.claims.documents.domain.model.Document;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope of {@code GET /api/v1/documents?claimId=...}.
 *
 * @param content the documents of the current page
 * @param page zero-based index of the current page
 * @param size requested page size
 * @param totalElements total number of live documents of the claim
 * @param totalPages total number of pages
 */
public record DocumentPageResponse(
    List<DocumentResponse> content, int page, int size, long totalElements, int totalPages) {

  /**
   * @param page source page of domain metadata
   * @return the API envelope
   */
  public static DocumentPageResponse from(Page<Document> page) {
    return new DocumentPageResponse(
        page.getContent().stream().map(DocumentResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
