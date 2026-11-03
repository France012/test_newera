package it.enterprise.claims.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import it.enterprise.claims.bff.web.dto.ClaimSummaryResponse;
import java.util.List;

/**
 * Page envelope returned by {@code core-app} on {@code GET /api/v1/claims}.
 *
 * <p>It is a downstream transport type, not an API type: the BFF uses {@code totalElements} for the
 * dashboard counters and forwards only {@code content} to the browser.
 *
 * @param content claims of the current page
 * @param page zero-based page index
 * @param size page size
 * @param totalElements total number of matching claims
 * @param totalPages total number of pages
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimPagePayload(
    List<ClaimSummaryResponse> content, int page, int size, long totalElements, int totalPages) {

  /** Compact constructor: downstream payloads must never leak {@code null} collections upwards. */
  public ClaimPagePayload {
    content = content == null ? List.of() : List.copyOf(content);
  }
}
