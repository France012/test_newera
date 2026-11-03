package it.enterprise.claims.bff.web.dto;

import java.util.List;

/**
 * Aggregated view of {@code GET /bff/v1/claims/{id}}: the claim plus the documents that
 * document-service holds for it, fetched in one round trip from the browser's point of view.
 *
 * @param claim the claim as returned by core-app
 * @param documents the live documents of the claim, never {@code null}
 */
public record ClaimDetailResponse(
    ClaimSummaryResponse claim, List<DocumentSummaryResponse> documents) {

  /** Compact constructor: a client never has to null-check the collection. */
  public ClaimDetailResponse {
    documents = documents == null ? List.of() : List.copyOf(documents);
  }
}
