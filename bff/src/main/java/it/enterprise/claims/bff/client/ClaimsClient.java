package it.enterprise.claims.bff.client;

import it.enterprise.claims.bff.web.dto.ClaimSummaryResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for {@code core-app}.
 *
 * <p>The base URL is injected from configuration ({@code services.core-app.base-url}, environment
 * variable {@code CORE_APP_BASE_URL}): no production host is ever hardcoded. Transport errors are
 * deliberately left as {@code RestClientException}s - the controller advice turns them into RFC
 * 9457 problems, so this class stays a thin protocol adapter.
 */
@Component
public class ClaimsClient {

  private static final String CLAIMS_PATH = "/api/v1/claims";
  private static final String DEFAULT_SORT = "openedAt,desc";

  private final RestClient coreAppRestClient;

  /**
   * @param coreAppRestClient pre-configured client bound to the core-app base URL
   */
  public ClaimsClient(@Qualifier("coreAppRestClient") RestClient coreAppRestClient) {
    this.coreAppRestClient = coreAppRestClient;
  }

  /**
   * @param status optional status filter, {@code null} means all statuses
   * @param page zero-based page index
   * @param size page size
   * @return one page of claims
   */
  public ClaimPagePayload findClaims(String status, int page, int size) {
    ClaimPagePayload payload =
        coreAppRestClient
            .get()
            .uri(
                uri -> {
                  uri.path(CLAIMS_PATH)
                      .queryParam("page", page)
                      .queryParam("size", size)
                      .queryParam("sort", DEFAULT_SORT);
                  if (status != null && !status.isBlank()) {
                    uri.queryParam("status", status);
                  }
                  return uri.build();
                })
            .retrieve()
            .body(ClaimPagePayload.class);
    return requirePayload(payload, "claim page");
  }

  /**
   * @param claimId identifier of the claim
   * @return the claim
   */
  public ClaimSummaryResponse findClaim(UUID claimId) {
    ClaimSummaryResponse claim =
        coreAppRestClient
            .get()
            .uri(CLAIMS_PATH + "/{claimId}", claimId)
            .retrieve()
            .body(ClaimSummaryResponse.class);
    if (claim == null) {
      throw new IllegalStateException("core-app returned an empty body for claim " + claimId);
    }
    return claim;
  }

  /**
   * Counts the claims in a given status by asking for a single-element page and reading {@code
   * totalElements}: no dedicated count endpoint is needed, and the cost is one indexed query
   * downstream.
   *
   * @param status status to count
   * @return number of claims in that status
   */
  public long countByStatus(String status) {
    return findClaims(status, 0, 1).totalElements();
  }

  private static ClaimPagePayload requirePayload(ClaimPagePayload payload, String what) {
    if (payload == null) {
      throw new IllegalStateException("core-app returned an empty body for " + what);
    }
    return payload;
  }
}
