package it.enterprise.claims.core.application;

import it.enterprise.claims.core.domain.model.AuditEvent;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.port.AuditEventPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Writes the audit trail (decision A5).
 *
 * <p>One event per fact, appended in the same transaction as the fact itself (no async, no outbox
 * in phase 1): if the business write rolls back, the audit row rolls back with it, so the trail can
 * never describe something that did not happen.
 *
 * <p>The correlation id is read from the SLF4J {@link MDC} and validated against the format the
 * database accepts ({@code ^[A-Za-z0-9._:-]{1,64}$}); anything else is dropped instead of failing
 * the business operation.
 */
@Component
public class AuditRecorder {

  /** MDC key populated by the request filter, when present. */
  public static final String REQUEST_ID_MDC_KEY = "requestId";

  private static final Pattern REQUEST_ID_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  private final AuditEventPort auditEventPort;
  private final Clock clock;

  /**
   * @param auditEventPort append-only port of the audit table
   * @param clock clock used to timestamp the event (injectable in tests)
   */
  public AuditRecorder(AuditEventPort auditEventPort, Clock clock) {
    this.auditEventPort = auditEventPort;
    this.clock = clock;
  }

  /**
   * Appends one successful event.
   *
   * @param eventType business event type
   * @param entityType polymorphic entity type, e.g. {@code CLAIM}
   * @param entityId identifier of the entity the event is about
   * @param claimId claim the event belongs to, may be {@code null} for actor-level events
   * @param actorId actor who performed the action, may be {@code null}
   * @param details free structured payload, may be {@code null}
   * @return the appended event
   */
  public AuditEvent record(
      AuditEventType eventType,
      String entityType,
      UUID entityId,
      UUID claimId,
      UUID actorId,
      Map<String, Object> details) {
    return auditEventPort.append(
        new AuditEvent(
            UUID.randomUUID(),
            claimId,
            actorId,
            eventType,
            entityType,
            entityId,
            AuditEvent.SUCCESS,
            OffsetDateTime.now(clock),
            currentRequestId(),
            details));
  }

  private static String currentRequestId() {
    String requestId = MDC.get(REQUEST_ID_MDC_KEY);
    if (requestId == null || !REQUEST_ID_FORMAT.matcher(requestId).matches()) {
      return null;
    }
    return requestId;
  }
}
