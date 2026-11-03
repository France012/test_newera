package it.enterprise.claims.core.application.exception;

/**
 * Thrown when a syntactically valid request violates a business rule that Bean Validation cannot
 * express - mapped to HTTP 400.
 */
public class InvalidClaimRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message explanation returned to the caller in the {@code ProblemDetail.detail} field
   */
  public InvalidClaimRequestException(String message) {
    super(message);
  }
}
