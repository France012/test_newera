package it.enterprise.claims.core.domain.port;

import it.enterprise.claims.core.domain.model.AuditEvent;
import it.enterprise.claims.core.domain.model.AuditEventType;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Outbound port of the append-only audit trail (decision A5/DD-11).
 *
 * <p><strong>Append-only by contract</strong>: the port exposes {@link #append(AuditEvent)} plus
 * finders, and nothing else. There is no {@code update} and no {@code delete} here, and there is no
 * database trigger either (DD-8): immutability is guaranteed by the absence of a write path, not by
 * hidden database behaviour.
 */
public interface AuditEventPort {

  /**
   * Appends one event. Must be called in the same transaction as the fact it describes.
   *
   * @param event event to append
   * @return the appended event
   */
  AuditEvent append(AuditEvent event);

  /**
   * @param claimId claim whose trail is requested
   * @param eventType optional filter, may be {@code null}
   * @param pageable page request (newest first)
   * @return the matching page of events
   */
  Page<AuditEvent> findByClaimId(UUID claimId, AuditEventType eventType, Pageable pageable);
}
