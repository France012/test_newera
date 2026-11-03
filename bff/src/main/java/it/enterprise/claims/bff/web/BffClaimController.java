package it.enterprise.claims.bff.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.bff.client.ClaimsClient;
import it.enterprise.claims.bff.client.DocumentsClient;
import it.enterprise.claims.bff.web.dto.ClaimDetailResponse;
import it.enterprise.claims.bff.web.dto.ClaimSummaryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claim endpoints of the BFF, base path {@code /bff/v1/claims} (blueprint §2.5).
 *
 * <p>Read-only by design in phase 1: the browser composes claims and documents in a single call,
 * and mutations keep going to the owning service so that the aggregate stays authoritative where
 * the business rules live.
 */
@RestController
@RequestMapping(value = "/bff/v1/claims", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Claims", description = "Aggregated claim views for the frontend")
public class BffClaimController {

  private static final int MAX_PAGE_SIZE = 100;

  private final ClaimsClient claimsClient;
  private final DocumentsClient documentsClient;

  /**
   * @param claimsClient core-app client
   * @param documentsClient document-service client
   */
  public BffClaimController(ClaimsClient claimsClient, DocumentsClient documentsClient) {
    this.claimsClient = claimsClient;
    this.documentsClient = documentsClient;
  }

  /**
   * @param status optional status filter
   * @param page zero-based page index
   * @param size page size, clamped to 100
   * @return the claims of the requested page
   */
  @GetMapping
  @Operation(summary = "List claims (aggregated passthrough of core-app)")
  public List<ClaimSummaryResponse> claims(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return claimsClient
        .findClaims(status, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))
        .content();
  }

  /**
   * Aggregates the claim with its documents.
   *
   * @param claimId identifier of the claim
   * @return claim plus documents
   */
  @GetMapping("/{claimId}")
  @Operation(summary = "Get a claim with its documents in a single round trip")
  public ClaimDetailResponse claim(@PathVariable UUID claimId) {
    ClaimSummaryResponse claim = claimsClient.findClaim(claimId);
    return new ClaimDetailResponse(claim, documentsClient.findByClaimId(claimId));
  }
}
