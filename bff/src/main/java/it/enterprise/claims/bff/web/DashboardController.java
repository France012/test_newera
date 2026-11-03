package it.enterprise.claims.bff.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.bff.client.ClaimsClient;
import it.enterprise.claims.bff.web.dto.DashboardSummaryResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard counters for the landing page, base path {@code /bff/v1/dashboard} (blueprint §2.5).
 *
 * <p>Counters are derived from core-app's paged endpoints: one request per status with {@code
 * size=1}, reading {@code totalElements}. That keeps the contract of the owning service unchanged
 * (no extra count endpoint) at the cost of one indexed query per status - acceptable for a
 * dashboard that is loaded once per session.
 */
@RestController
@RequestMapping(value = "/bff/v1/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Dashboard", description = "Aggregated counters for the frontend landing page")
public class DashboardController {

  /** Lifecycle order of the claim states, mirrored from {@code core-app ClaimStatus}. */
  private static final List<String> TRACKED_STATUSES =
      List.of(
          "DRAFT",
          "SUBMITTED",
          "UNDER_REVIEW",
          "INTEGRATION_REQUESTED",
          "APPROVED",
          "REJECTED",
          "CLOSED");

  /** States that still require an operator action; they add up to {@code totalOpen}. */
  private static final List<String> OPEN_STATUSES =
      List.of("DRAFT", "SUBMITTED", "UNDER_REVIEW", "INTEGRATION_REQUESTED");

  private final ClaimsClient claimsClient;

  /**
   * @param claimsClient core-app client
   */
  public DashboardController(ClaimsClient claimsClient) {
    this.claimsClient = claimsClient;
  }

  /**
   * @return total open claims plus the count per status
   */
  @GetMapping("/summary")
  @Operation(summary = "Claim counters by status for the dashboard")
  public DashboardSummaryResponse summary() {
    Map<String, Long> byStatus = new LinkedHashMap<>();
    long totalOpen = 0L;
    for (String status : TRACKED_STATUSES) {
      long count = claimsClient.countByStatus(status);
      byStatus.put(status, count);
      if (OPEN_STATUSES.contains(status)) {
        totalOpen += count;
      }
    }
    return new DashboardSummaryResponse(totalOpen, byStatus);
  }
}
