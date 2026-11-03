package it.enterprise.claims.bff.web.dto;

import java.util.Map;

/**
 * Response of {@code GET /bff/v1/dashboard/summary}.
 *
 * @param totalOpen claims that still require work, i.e. every status except {@code APPROVED},
 *     {@code REJECTED} and {@code CLOSED}
 * @param byStatus number of claims per status, in lifecycle order
 */
public record DashboardSummaryResponse(long totalOpen, Map<String, Long> byStatus) {}
