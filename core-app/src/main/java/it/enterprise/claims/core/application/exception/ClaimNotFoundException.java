package it.enterprise.claims.core.application.exception;

import java.util.UUID;

/** Thrown when a claim id does not resolve to any row - mapped to HTTP 404. */
public class ClaimNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID claimId;

  /**
   * @param claimId the identifier that was not found
   */
  public ClaimNotFoundException(UUID claimId) {
    super("Claim " + claimId + " not found");
    this.claimId = claimId;
  }

  /**
   * @return the identifier that was not found
   */
  public UUID getClaimId() {
    return claimId;
  }
}
