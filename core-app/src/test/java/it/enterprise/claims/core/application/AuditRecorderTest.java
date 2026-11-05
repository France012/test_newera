package it.enterprise.claims.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.enterprise.claims.core.domain.model.AuditEvent;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.port.AuditEventPort;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests of the audit trail writer: shape of the appended event, no hidden side effects. */
@ExtendWith(MockitoExtension.class)
class AuditRecorderTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2025-03-10T09:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2025-03-10T09:00:00Z");
  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Mock private AuditEventPort auditEventPort;

  private AuditRecorder auditRecorder;

  @BeforeEach
  void setUp() {
    auditRecorder = new AuditRecorder(auditEventPort, FIXED_CLOCK);
  }

  @Test
  @DisplayName("record() appends one SUCCESS event stamped with the injected clock")
  void recordAppendsEvent() {
    when(auditEventPort.append(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AuditEvent event =
        auditRecorder.record(
            AuditEventType.CLAIM_STATUS_CHANGED,
            "CLAIM",
            CLAIM_ID,
            CLAIM_ID,
            ACTOR_ID,
            Map.of("fromStatus", "DRAFT"));

    assertThat(event.id()).isNotNull();
    assertThat(event.eventType()).isEqualTo(AuditEventType.CLAIM_STATUS_CHANGED);
    assertThat(event.entityType()).isEqualTo("CLAIM");
    assertThat(event.entityId()).isEqualTo(CLAIM_ID);
    assertThat(event.claimId()).isEqualTo(CLAIM_ID);
    assertThat(event.actorId()).isEqualTo(ACTOR_ID);
    assertThat(event.outcome()).isEqualTo(AuditEvent.SUCCESS);
    assertThat(event.occurredAt()).isEqualTo(NOW);
    assertThat(event.details()).containsEntry("fromStatus", "DRAFT");
  }

  @Test
  @DisplayName("record() keeps a null actor and null details representable")
  void recordAllowsAnonymousEvents() {
    when(auditEventPort.append(any(AuditEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    auditRecorder.record(AuditEventType.CLAIM_OPENED, "CLAIM", CLAIM_ID, CLAIM_ID, null, null);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventPort).append(captor.capture());
    assertThat(captor.getValue().actorId()).isNull();
    assertThat(captor.getValue().details()).isEmpty();
    assertThat(captor.getValue().requestId()).isNull();
  }
}
