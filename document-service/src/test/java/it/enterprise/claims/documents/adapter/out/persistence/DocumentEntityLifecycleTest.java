package it.enterprise.claims.documents.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the document persistence model introduced by TK-64FBC8 ({@code
 * V2__document_versioning_retention.sql}).
 *
 * <p>Pure unit tests, no database: they pin the version-chain semantics ({@code document_key} +
 * {@code version_number} + {@code is_current}), the backfill default that keeps the V1 constructor
 * working, the retention policy helpers and the soft delete. The schema ⇄ entity parity is proved
 * by {@link SchemaMigrationContractTest}; here only the mapping behaviour is asserted.
 */
class DocumentEntityLifecycleTest {

  private static final String SHA =
      "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

  private static DocumentJpaEntity document(UUID id) {
    return new DocumentJpaEntity(
        id,
        UUID.randomUUID(),
        "perizia.pdf",
        "application/pdf",
        1024L,
        "APPRAISAL",
        SHA,
        "claim/1/perizia.pdf");
  }

  @Test
  @DisplayName("the V1 constructor still works: the row is the first and current version of itself")
  void legacyConstructorBackfillsTheVersionChain() {
    UUID id = UUID.randomUUID();
    DocumentJpaEntity entity = document(id);

    assertThat(entity.getDocumentKey()).as("backfilled in the constructor").isEqualTo(id);
    assertThat(entity.getVersionNumber()).isEqualTo(1);
    assertThat(entity.isCurrent()).isTrue();
    assertThat(entity.getSupersedesId()).isNull();
    assertThat(entity.getRetentionUntil()).isNull();
    assertThat(entity.isLegalHold()).isFalse();
  }

  @Test
  @DisplayName("onCreate fills document_key/version_number for rows built field by field")
  void onCreateFillsTheVersionDefaults() {
    UUID id = UUID.randomUUID();
    DocumentJpaEntity entity = document(id);
    entity.setDocumentKey(null);
    entity.setVersionNumber(null);

    entity.onCreate();

    assertThat(entity.getDocumentKey()).isEqualTo(id);
    assertThat(entity.getVersionNumber()).isEqualTo(1);
    assertThat(entity.getUploadedAt()).isNotNull();
  }

  @Test
  @DisplayName("a new version demotes the previous one instead of forking the chain")
  void supersedingSwapsTheCurrentFlag() {
    UUID documentKey = UUID.randomUUID();
    DocumentJpaEntity previous = document(UUID.randomUUID());
    previous.setDocumentKey(documentKey);

    DocumentJpaEntity next =
        new DocumentJpaEntity(
            UUID.randomUUID(),
            documentKey,
            UUID.randomUUID(),
            "perizia-v2.pdf",
            "application/pdf",
            2048L,
            "APPRAISAL",
            SHA,
            "claim/1/perizia-v2.pdf");
    next.setVersionNumber(previous.getVersionNumber() + 1);
    next.setSupersedesId(previous.getId());

    previous.markSuperseded();

    assertThat(previous.isCurrent()).isFalse();
    assertThat(next.isCurrent()).isTrue();
    assertThat(next.getVersionNumber()).isEqualTo(2);
    assertThat(next.getSupersedesId()).isEqualTo(previous.getId());
    assertThat(next.getDocumentKey()).isEqualTo(previous.getDocumentKey());

    previous.markCurrent();
    assertThat(previous.isCurrent()).isTrue();
  }

  @Test
  @DisplayName(
      "retention_until and legal_hold are applied together and are independent of the version")
  void retentionPolicyIsMutable() {
    DocumentJpaEntity entity = document(UUID.randomUUID());
    entity.onCreate();

    LocalDate until = entity.getUploadedAt().toLocalDate().plusYears(10);
    entity.updateRetention(until, true);

    assertThat(entity.getRetentionUntil()).isEqualTo(until);
    assertThat(entity.isLegalHold()).isTrue();
    assertThat(entity.isCurrent()).as("retention must not touch the version chain").isTrue();

    entity.updateRetention(null, false);
    assertThat(entity.getRetentionUntil()).isNull();
    assertThat(entity.isLegalHold()).isFalse();
  }

  @Test
  @DisplayName("key_ref is stored but never derived from storage_key")
  void keyRefIsOpaqueAndOptional() {
    DocumentJpaEntity entity = document(UUID.randomUUID());
    assertThat(entity.getKeyRef()).as("KMS is out of scope: the column may be null").isNull();

    entity.setKeyRef("arn:aws:kms:eu-south-1:1:key/abc");
    assertThat(entity.getKeyRef()).isNotEqualTo(entity.getStorageKey());
    assertThat(entity.getStorageKey())
        .as("storage_key keeps identifying the physical object")
        .isEqualTo("claim/1/perizia.pdf");
  }

  @Test
  @DisplayName("soft delete is idempotent and keeps the row readable")
  void softDeleteKeepsTheRow() {
    DocumentJpaEntity entity = document(UUID.randomUUID());
    entity.onCreate();

    assertThat(entity.isDeleted()).isFalse();
    entity.markDeleted(null);
    assertThat(entity.isDeleted()).isTrue();
    assertThat(entity.getDeletedAt()).isNotNull();
    assertThat(entity.isCurrent())
        .as("a deleted row is not served, whatever the flag says")
        .isTrue();
  }
}
