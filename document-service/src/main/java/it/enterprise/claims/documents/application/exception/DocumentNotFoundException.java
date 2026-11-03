package it.enterprise.claims.documents.application.exception;

import java.util.UUID;

/** Thrown when a document id does not resolve to a live row - mapped to HTTP 404. */
public class DocumentNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID documentId;

  /**
   * @param documentId the identifier that was not found
   */
  public DocumentNotFoundException(UUID documentId) {
    super("Document " + documentId + " not found");
    this.documentId = documentId;
  }

  /**
   * @return the identifier that was not found
   */
  public UUID getDocumentId() {
    return documentId;
  }
}
