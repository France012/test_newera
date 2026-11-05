package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload of {@code PATCH /api/v1/claims/{id}/status}.
 *
 * @param status requested target state, validated against the {@link ClaimStatus} state machine
 * @param note optional reason stored in the history and in the audit trail
 * @param actorId actor who performs the transition; {@code null} until OIDC is in place (ADR-0005),
 *     an unknown id is a 404
 */
public record ClaimStatusUpdateRequest(
    @NotNull ClaimStatus status, @Size(max = 500) String note, UUID actorId) {

  /**
   * Backwards-compatible constructor for a transition with no asserted actor.
   *
   * @param status requested target state
   * @param note optional reason
   */
  public ClaimStatusUpdateRequest(ClaimStatus status, String note) {
    this(status, note, null);
  }
}
