package it.enterprise.claims.bff.client;

import it.enterprise.claims.bff.web.dto.DocumentSummaryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for {@code document-service}.
 *
 * <p>Base URL from configuration ({@code services.document-service.base-url}, environment variable
 * {@code DOCUMENT_SERVICE_BASE_URL}).
 */
@Component
public class DocumentsClient {

  private static final String DOCUMENTS_PATH = "/api/v1/documents";
  private static final int MAX_DOCUMENTS_PER_CLAIM = 200;

  private final RestClient documentServiceRestClient;

  /**
   * @param documentServiceRestClient pre-configured client bound to the document-service base URL
   */
  public DocumentsClient(
      @Qualifier("documentServiceRestClient") RestClient documentServiceRestClient) {
    this.documentServiceRestClient = documentServiceRestClient;
  }

  /**
   * @param claimId claim whose documents are requested
   * @return the live documents of the claim, ordered by document-service (newest first)
   */
  public List<DocumentSummaryResponse> findByClaimId(UUID claimId) {
    DocumentPagePayload payload =
        documentServiceRestClient
            .get()
            .uri(
                uri ->
                    uri.path(DOCUMENTS_PATH)
                        .queryParam("claimId", claimId)
                        .queryParam("size", MAX_DOCUMENTS_PER_CLAIM)
                        .build())
            .retrieve()
            .body(DocumentPagePayload.class);
    return payload == null ? List.of() : payload.content();
  }
}
