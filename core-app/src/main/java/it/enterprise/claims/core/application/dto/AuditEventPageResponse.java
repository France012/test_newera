package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.AuditEvent;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope of {@code GET /api/v1/claims/{claimId}/audit-events}.
 *
 * @param content the events of the current page, newest first
 * @param page zero-based index of the current page
 * @param size requested page size
 * @param totalElements total number of matching events
 * @param totalPages total number of pages
 */
public record AuditEventPageResponse(
    List<AuditEventResponse> content, int page, int size, long totalElements, int totalPages) {

  /**
   * @param page source page of domain events
   * @return the API envelope
   */
  public static AuditEventPageResponse from(Page<AuditEvent> page) {
    return new AuditEventPageResponse(
        page.getContent().stream().map(AuditEventResponse::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
