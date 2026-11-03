package it.enterprise.claims.core.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-access port over {@code coreapp.claim_status_history}.
 *
 * <p>The audit trail is append-only and must be read in chronological order, so the ordering is
 * part of the method name (and is backed by {@code idx_claim_status_history_claim}).
 */
public interface ClaimStatusHistoryJpaRepository
    extends JpaRepository<ClaimStatusHistoryJpaEntity, UUID> {

  List<ClaimStatusHistoryJpaEntity> findByClaimIdOrderByChangedAtAsc(UUID claimId);
}
