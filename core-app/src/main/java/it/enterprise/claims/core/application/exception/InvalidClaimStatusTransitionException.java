package it.enterprise.claims.core.application.exception;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import java.util.Set;
import java.util.UUID;

/**
 * Thrown when a caller asks for a status change the state machine does not allow - mapped to HTTP
 * 409 Conflict (blueprint §2.3).
 */
public class InvalidClaimStatusTransitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID claimId;
  private final ClaimStatus fromStatus;
  private final ClaimStatus toStatus;

  /**
   * @param claimId claim being updated
   * @param fromStatus current state
   * @param toStatus requested state
   */
  public InvalidClaimStatusTransitionException(
      UUID claimId, ClaimStatus fromStatus, ClaimStatus toStatus) {
    super(
        "Transition "
            + fromStatus
            + " -> "
            + toStatus
            + " is not allowed for claim "
            + claimId
            + "; allowed targets: "
            + fromStatus.allowedTransitions());
    this.claimId = claimId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
  }

  /**
   * @return the claim that was being updated
   */
  public UUID getClaimId() {
    return claimId;
  }

  /**
   * @return the state the claim was in
   */
  public ClaimStatus getFromStatus() {
    return fromStatus;
  }

  /**
   * @return the state the caller asked for
   */
  public ClaimStatus getToStatus() {
    return toStatus;
  }

  /**
   * @return the states that would have been accepted
   */
  public Set<ClaimStatus> getAllowedTargets() {
    return fromStatus.allowedTransitions();
  }
}
