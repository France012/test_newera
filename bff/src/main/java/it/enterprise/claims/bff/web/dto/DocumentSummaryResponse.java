package it.enterprise.claims.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Document summary as returned by {@code document-service} {@code DocumentResponse}.
 *
 * <p>The BFF exposes only what the UI needs: the storage key of the content is internal to
 * document-service.
 *
 * @param id document identifier
 * @param claimId claim the document belongs to
 * @param fileName original file name
 * @param contentType MIME type
 * @param sizeBytes size in bytes
 * @param documentType business classification
 * @param checksumSha256 hex SHA-256 of the content
 * @param uploadedAt upload timestamp
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentSummaryResponse(
    UUID id,
    UUID claimId,
    String fileName,
    String contentType,
    Long sizeBytes,
    String documentType,
    String checksumSha256,
    OffsetDateTime uploadedAt) {}
