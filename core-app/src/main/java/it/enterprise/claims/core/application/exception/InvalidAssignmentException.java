package it.enterprise.claims.core.application.exception;

/**
 * Thrown when an assignment cannot be created because of the state of the actor or of the claim -
 * mapped to HTTP 409.
 *
 * <p>Examples: the actor is disabled, or two callers raced on the partial unique index {@code
 * uk_claim_assignments_active_role}.
 */
public class InvalidAssignmentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message explanation returned to the caller in {@code ProblemDetail.detail}
   */
  public InvalidAssignmentException(String message) {
    super(message);
  }

  /**
   * @param message explanation returned to the caller
   * @param cause low level cause (e.g. a constraint violation)
   */
  public InvalidAssignmentException(String message, Throwable cause) {
    super(message, cause);
  }
}
