package it.enterprise.claims.documents.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data-access port over {@code documents.documents}.
 *
 * <p>All read methods exclude logically deleted rows by contract: the soft-delete filter is part of
 * the query derivation ({@code DeletedAtIsNull}) so it cannot be forgotten by callers.
 *
 * <p>Versioning queries (V2, decision A6) always go through {@code document_key} + {@code
 * version_number}; the "is this the current version?" question is answered by the {@code
 * is_current} flag ({@code current} in the entity) and not by a {@code MAX(version_number)} scan,
 * so a concurrent upload cannot silently win a race — the partial unique index {@code
 * uk_documents_current_version} rejects the second current row.
 */
public interface DocumentJpaRepository extends JpaRepository<DocumentJpaEntity, UUID> {

  Optional<DocumentJpaEntity> findByIdAndDeletedAtIsNull(UUID id);

  Page<DocumentJpaEntity> findByClaimIdAndDeletedAtIsNull(UUID claimId, Pageable pageable);

  /**
   * @return live documents of a claim, current versions only (the API default)
   */
  Page<DocumentJpaEntity> findByClaimIdAndCurrentTrueAndDeletedAtIsNull(
      UUID claimId, Pageable pageable);

  long countByClaimIdAndDeletedAtIsNull(UUID claimId);

  /**
   * @return the live current version of a {@code document_key}, if any
   */
  Optional<DocumentJpaEntity> findByDocumentKeyAndCurrentTrueAndDeletedAtIsNull(UUID documentKey);

  /**
   * @param documentKey logical document identity
   * @return every live version of the document, newest first
   */
  List<DocumentJpaEntity> findByDocumentKeyAndDeletedAtIsNullOrderByVersionNumberDesc(
      UUID documentKey);

  /**
   * Highest existing version number of a {@code document_key}.
   *
   * <p>Written as explicit JPQL because {@code max()} over an empty set must return a value ({@code
   * coalesce}) instead of {@code null}.
   *
   * @param documentKey logical document identity
   * @return the highest version number, {@code 0} when the key has no version yet
   */
  @Query(
      "select coalesce(max(d.versionNumber), 0) from DocumentJpaEntity d"
          + " where d.documentKey = :documentKey")
  int findMaxVersionNumber(@Param("documentKey") UUID documentKey);
}
