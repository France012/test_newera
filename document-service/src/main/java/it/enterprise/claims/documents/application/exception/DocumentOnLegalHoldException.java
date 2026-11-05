package it.enterprise.claims.documents.application.exception;

import java.util.UUID;

/**
 * Thrown when a document on legal hold is deleted - mapped to HTTP 409.
 *
 * <p>A legal hold overrides the retention date: the document must stay readable until the hold is
 * lifted, whatever the retention policy says (decision A7/DD-14).
 */
public class DocumentOnLegalHoldException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID documentId;

  /**
   * @param documentId the document that cannot be deleted
   */
  public DocumentOnLegalHoldException(UUID documentId) {
    super("Document " + documentId + " is on legal hold and cannot be deleted");
    this.documentId = documentId;
  }

  /**
   * @return the document that cannot be deleted
   */
  public UUID getDocumentId() {
    return documentId;
  }
}
