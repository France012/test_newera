package it.enterprise.claims.core.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port over the {@code coreapp.claim_documents} link table.
 *
 * <p>{@code documentId} is a logical reference: the binary content and its metadata live in
 * document-service. Linking is idempotent - attaching an already linked document is a no-op, which
 * is what makes {@code POST /api/v1/claims/{id}/documents} safe to retry.
 */
public interface ClaimDocumentPort {

  /**
   * @param claimId claim whose documents are requested
   * @return the attached document ids, empty when none
   */
  List<UUID> findDocumentIds(UUID claimId);

  /**
   * Attaches the given documents to the claim, skipping the ones already linked.
   *
   * @param claimId claim to update
   * @param documentIds documents to attach
   * @return the complete list of document ids attached to the claim after the call
   */
  List<UUID> link(UUID claimId, List<UUID> documentIds);
}
