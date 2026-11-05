package it.enterprise.claims.core.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Data-access port over {@code coreapp.audit_events}.
 *
 * <p><strong>Append-only</strong> (decision A5/DD-11): this interface extends the marker {@link
 * Repository} instead of {@code JpaRepository} on purpose — no {@code delete}, no {@code
 * deleteAll}, no {@code saveAndFlush} of a managed dirty entity is exposed, so an accidental {@code
 * UPDATE} or {@code DELETE} of the audit trail does not even compile. {@link
 * #save(AuditEventJpaEntity)} on a {@link org.hibernate.annotations.Immutable} entity is an insert.
 */
public interface AuditEventJpaRepository extends Repository<AuditEventJpaEntity, UUID> {

  /**
   * Appends one audit row.
   *
   * @param event the row to insert
   * @return the inserted row
   */
  AuditEventJpaEntity save(AuditEventJpaEntity event);

  /**
   * @param claimId claim id
   * @param pageable paging/sorting (newest first)
   * @return the audit trail of one claim
   */
  Page<AuditEventJpaEntity> findByClaimId(UUID claimId, Pageable pageable);

  /**
   * @param claimId claim id
   * @param eventType business event type, e.g. {@code CLAIM_STATUS_CHANGED}
   * @param pageable paging/sorting
   * @return the audit trail of one claim filtered by event type
   */
  Page<AuditEventJpaEntity> findByClaimIdAndEventType(
      UUID claimId, String eventType, Pageable pageable);

  /**
   * @param entityType polymorphic entity type, e.g. {@code ACTOR}
   * @param entityId entity id
   * @return every event recorded for that entity, newest first
   */
  List<AuditEventJpaEntity> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
      String entityType, UUID entityId);
}
