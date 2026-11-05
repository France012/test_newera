package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import it.enterprise.claims.core.domain.model.ClaimStatusHistory;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of {@code GET /api/v1/claims/{id}/status-history}.
 *
 * @param id history row identifier
 * @param fromStatus previous state, {@code null} for the opening transition
 * @param toStatus state the claim moved to
 * @param note free-text reason, may be {@code null}
 * @param changedAt timestamp of the transition
 * @param actorId actor who performed the transition, {@code null} for rows written before TK-64FBC8
 * @param actorDisplayName display name of that actor, may be {@code null}
 */
public record ClaimStatusHistoryResponse(
    UUID id,
    ClaimStatus fromStatus,
    ClaimStatus toStatus,
    String note,
    OffsetDateTime changedAt,
    UUID actorId,
    String actorDisplayName) {

  /**
   * @param history domain entry to expose
   * @return the API representation
   */
  public static ClaimStatusHistoryResponse from(ClaimStatusHistory history) {
    return new ClaimStatusHistoryResponse(
        history.id(),
        history.fromStatus(),
        history.toStatus(),
        history.note(),
        history.changedAt(),
        history.actorId(),
        history.actorDisplayName());
  }
}
