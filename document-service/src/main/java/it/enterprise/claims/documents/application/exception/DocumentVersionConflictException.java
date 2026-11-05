package it.enterprise.claims.documents.application.exception;

import java.util.UUID;

/**
 * Thrown when two callers create a version of the same document at the same time - mapped to HTTP
 * 409.
 *
 * <p>The loser of the race is rejected by one of the unique constraints of {@code
 * documents.documents} ({@code uk_documents_document_key_version} or {@code
 * uk_documents_current_version}); the caller can retry and gets the next free version number.
 */
public class DocumentVersionConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID documentKey;

  /**
   * @param documentKey logical identity of the document whose version chain is contended
   * @param cause low level cause (constraint violation or optimistic-lock failure)
   */
  public DocumentVersionConflictException(UUID documentKey, Throwable cause) {
    super(
        "Concurrent creation of a new version for documentKey "
            + documentKey
            + "; retry the upload",
        cause);
    this.documentKey = documentKey;
  }

  /**
   * @return the logical identity of the contended document
   */
  public UUID getDocumentKey() {
    return documentKey;
  }
}
