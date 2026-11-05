package it.enterprise.claims.core.application.dto;

import it.enterprise.claims.core.domain.model.ClaimDocumentLink;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API representation of a claim ⇄ document link (blueprint §7.1).
 *
 * <p>Only the link is described here: the document metadata and its content live in
 * document-service, which the caller queries with {@code documentId}.
 *
 * @param documentId document-service identifier
 * @param linkedAt attachment timestamp
 * @param linkedByActorId actor who attached the document, may be {@code null}
 * @param unlinkedAt detach timestamp, {@code null} while the document is attached
 */
public record ClaimDocumentLinkResponse(
    UUID documentId, OffsetDateTime linkedAt, UUID linkedByActorId, OffsetDateTime unlinkedAt) {

  /**
   * @param link domain link to expose
   * @return the API representation
   */
  public static ClaimDocumentLinkResponse from(ClaimDocumentLink link) {
    return new ClaimDocumentLinkResponse(
        link.documentId(), link.linkedAt(), link.linkedByActorId(), link.unlinkedAt());
  }
}
