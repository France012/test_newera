package it.enterprise.claims.core.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.core.application.ClaimService;
import it.enterprise.claims.core.application.dto.AuditEventPageResponse;
import it.enterprise.claims.core.domain.model.AuditEventType;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit trail of a claim, base path {@code /api/v1/claims/{claimId}/audit-events} (blueprint §7.1).
 *
 * <p>The trail is append-only and read newest-first: the order is part of the contract, not a
 * caller choice, because the index {@code idx_audit_events_claim} is built on {@code (claim_id,
 * occurred_at desc)}.
 */
@RestController
@RequestMapping(
    value = "/api/v1/claims/{claimId}/audit-events",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Audit", description = "Append-only audit trail of a claim")
public class ClaimAuditEventController {

  private static final int MAX_PAGE_SIZE = 100;

  private final ClaimService claimService;

  /**
   * @param claimService claim use cases (also validates that the claim exists)
   */
  public ClaimAuditEventController(ClaimService claimService) {
    this.claimService = claimService;
  }

  /**
   * @param claimId claim whose trail is requested
   * @param eventType optional filter on the business event type
   * @param page zero-based page index
   * @param size page size, clamped to 100
   * @return one page of audit events, newest first
   */
  @GetMapping
  @Operation(summary = "List the audit events of a claim, newest first")
  public AuditEventPageResponse list(
      @PathVariable UUID claimId,
      @RequestParam(required = false) AuditEventType eventType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PageRequest pageRequest =
        PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "occurredAt"));
    return claimService.auditEvents(claimId, eventType, pageRequest);
  }
}
