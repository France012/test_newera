package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Claim;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope of {@code GET /api/v1/claims} (blueprint §2.3).
 *
 * @param content the claims of the current page
 * @param page zero-based index of the current page
 * @param size requested page size
 * @param totalElements total number of matching claims
 * @param totalPages total number of pages
 */
public record ClaimPageResponse(
    List<ClaimResponse> content, int page, int size, long totalElements, int totalPages) {

  /**
   * @param page source page of domain claims
   * @return the API envelope
   */
  public static ClaimPageResponse from(Page<Claim> page) {
    return new ClaimPageResponse(
        page.getContent().stream().map(ClaimResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
