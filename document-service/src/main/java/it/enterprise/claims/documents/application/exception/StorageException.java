package it.enterprise.claims.documents.application.exception;

/** Thrown when the content store cannot be read or written - mapped to HTTP 500. */
public class StorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message technical description (logged, never returned verbatim to the client)
   */
  public StorageException(String message) {
    super(message);
  }

  /**
   * @param message technical description
   * @param cause underlying I/O failure
   */
  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
