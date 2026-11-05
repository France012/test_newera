package it.enterprise.claims.documents.application.exception;

/** Thrown when a retention date is not acceptable (e.g. in the past) - mapped to HTTP 400. */
public class RetentionViolationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message explanation returned to the caller in {@code ProblemDetail.detail}
   */
  public RetentionViolationException(String message) {
    super(message);
  }
}
