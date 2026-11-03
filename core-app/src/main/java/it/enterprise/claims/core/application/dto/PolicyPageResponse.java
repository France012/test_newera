package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.Policy;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope of {@code GET /api/v1/policies}.
 *
 * @param content the policies of the current page
 * @param page zero-based index of the current page
 * @param size requested page size
 * @param totalElements total number of policies
 * @param totalPages total number of pages
 */
public record PolicyPageResponse(
    List<PolicyResponse> content, int page, int size, long totalElements, int totalPages) {

  /**
   * @param page source page of domain policies
   * @return the API envelope
   */
  public static PolicyPageResponse from(Page<Policy> page) {
    return new PolicyPageResponse(
        page.getContent().stream().map(PolicyResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
