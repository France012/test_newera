package it.enterprise.claims.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import it.enterprise.claims.bff.web.dto.DocumentSummaryResponse;
import java.util.List;

/**
 * Page envelope returned by {@code document-service} on {@code GET /api/v1/documents?claimId=...}.
 *
 * @param content documents of the current page
 * @param page zero-based page index
 * @param size page size
 * @param totalElements total number of live documents of the claim
 * @param totalPages total number of pages
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentPagePayload(
    List<DocumentSummaryResponse> content, int page, int size, long totalElements, int totalPages) {

  /** Compact constructor: downstream payloads must never leak {@code null} collections upwards. */
  public DocumentPagePayload {
    content = content == null ? List.of() : List.copyOf(content);
  }
}
