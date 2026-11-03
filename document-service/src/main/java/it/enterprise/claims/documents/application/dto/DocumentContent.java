package it.enterprise.claims.documents.application.dto;

/**
 * Metadata plus raw bytes, returned by {@code GET /api/v1/documents/{id}/content}.
 *
 * @param metadata API representation of the document (file name, content type, checksum)
 * @param content raw bytes of the document
 */
public record DocumentContent(DocumentResponse metadata, byte[] content) {}
