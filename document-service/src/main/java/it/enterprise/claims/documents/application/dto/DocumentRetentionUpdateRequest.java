package it.enterprise.claims.documents.application.dto;

import java.time.LocalDate;

/**
 * Payload of {@code PATCH /api/v1/documents/{documentId}/retention} (blueprint §7.2).
 *
 * <p>Patch semantics: a {@code null} field is left untouched, so setting the legal hold does not
 * require repeating the retention date.
 *
 * @param retentionUntil new end of the mandatory retention period, may be {@code null}
 * @param legalHold new legal-hold flag, may be {@code null}
 */
public record DocumentRetentionUpdateRequest(LocalDate retentionUntil, Boolean legalHold) {}
