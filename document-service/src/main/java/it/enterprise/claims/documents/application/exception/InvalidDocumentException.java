package it.enterprise.claims.documents.application.exception;

/** Thrown when an upload is not acceptable - mapped to HTTP 400. */
public class InvalidDocumentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message explanation returned to the caller in {@code ProblemDetail.detail}
   */
  public InvalidDocumentException(String message) {
    super(message);
  }
}
