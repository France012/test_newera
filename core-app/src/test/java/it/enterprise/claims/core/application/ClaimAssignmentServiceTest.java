package it.enterprise.claims.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.enterprise.claims.core.application.dto.ClaimAssignmentRequest;
import it.enterprise.claims.core.application.dto.ClaimAssignmentResponse;
import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.InvalidAssignmentException;
import it.enterprise.claims.core.application.exception.UnknownRoleException;
import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.ActorType;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.model.Claim;
import it.enterprise.claims.core.domain.model.ClaimAssignment;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import it.enterprise.claims.core.domain.port.ActorRepositoryPort;
import it.enterprise.claims.core.domain.port.ClaimAssignmentPort;
import it.enterprise.claims.core.domain.port.ClaimRepositoryPort;
import it.enterprise.claims.core.domain.port.RoleRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests of the assignment use cases: role vocabulary, disabled actors, re-assignment. */
@ExtendWith(MockitoExtension.class)
class ClaimAssignmentServiceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2025-03-10T09:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2025-03-10T09:00:00Z");
  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PREVIOUS_ACTOR_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Mock private ClaimAssignmentPort claimAssignmentPort;
  @Mock private ActorRepositoryPort actorRepository;
  @Mock private RoleRepositoryPort roleRepository;
  @Mock private ClaimRepositoryPort claimRepository;
  @Mock private AuditRecorder auditRecorder;

  private ClaimAssignmentService claimAssignmentService;

  @BeforeEach
  void setUp() {
    claimAssignmentService =
        new ClaimAssignmentService(
            claimAssignmentPort,
            actorRepository,
            roleRepository,
            claimRepository,
            auditRecorder,
            FIXED_CLOCK);
  }

  @Test
  @DisplayName("assign() closes the previous holder and appends CLAIM_ASSIGNED")
  void assignReplacesThePreviousHolder() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim()));
    when(roleRepository.existsByCode("CLAIMS_OFFICER")).thenReturn(true);
    when(actorRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor(true)));
    when(claimAssignmentPort.findActive(CLAIM_ID, "CLAIMS_OFFICER"))
        .thenReturn(Optional.of(assignment(PREVIOUS_ACTOR_ID)));
    when(claimAssignmentPort.assign(any(ClaimAssignment.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ClaimAssignmentResponse response =
        claimAssignmentService.assign(
            CLAIM_ID, "CLAIMS_OFFICER", new ClaimAssignmentRequest(ACTOR_ID));

    assertThat(response.actorId()).isEqualTo(ACTOR_ID);
    assertThat(response.roleCode()).isEqualTo("CLAIMS_OFFICER");
    assertThat(response.actorDisplayName()).isEqualTo("Mario Rossi");
    assertThat(response.unassignedAt()).isNull();
    verify(auditRecorder)
        .record(eq(AuditEventType.CLAIM_ASSIGNED), eq("CLAIM"), eq(CLAIM_ID), eq(CLAIM_ID), any(),
            anyMap());
  }

  @Test
  @DisplayName("assign() rejects an unknown role code")
  void assignRejectsUnknownRole() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim()));
    when(roleRepository.existsByCode("NOPE")).thenReturn(false);

    assertThatThrownBy(
            () ->
                claimAssignmentService.assign(
                    CLAIM_ID, "NOPE", new ClaimAssignmentRequest(ACTOR_ID)))
        .isInstanceOf(UnknownRoleException.class);
    verify(claimAssignmentPort, never()).assign(any());
  }

  @Test
  @DisplayName("assign() rejects an unknown actor")
  void assignRejectsUnknownActor() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim()));
    when(roleRepository.existsByCode("CLAIMS_OFFICER")).thenReturn(true);
    when(actorRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                claimAssignmentService.assign(
                    CLAIM_ID, "CLAIMS_OFFICER", new ClaimAssignmentRequest(ACTOR_ID)))
        .isInstanceOf(ActorNotFoundException.class);
  }

  @Test
  @DisplayName("assign() rejects a disabled actor with a conflict")
  void assignRejectsDisabledActor() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim()));
    when(roleRepository.existsByCode("CLAIMS_OFFICER")).thenReturn(true);
    when(actorRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor(false)));

    assertThatThrownBy(
            () ->
                claimAssignmentService.assign(
                    CLAIM_ID, "CLAIMS_OFFICER", new ClaimAssignmentRequest(ACTOR_ID)))
        .isInstanceOf(InvalidAssignmentException.class)
        .hasMessageContaining("disabled");
  }

  @Test
  @DisplayName("assign() on an unknown claim is a not-found")
  void assignRejectsUnknownClaim() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                claimAssignmentService.assign(
                    CLAIM_ID, "CLAIMS_OFFICER", new ClaimAssignmentRequest(ACTOR_ID)))
        .isInstanceOf(ClaimNotFoundException.class);
  }

  @Test
  @DisplayName("unassign() closes the active holder and appends CLAIM_UNASSIGNED")
  void unassignClosesTheActiveHolder() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim()));
    when(claimAssignmentPort.unassign(CLAIM_ID, "CLAIMS_OFFICER"))
        .thenReturn(Optional.of(assignment(ACTOR_ID)));

    claimAssignmentService.unassign(CLAIM_ID, "CLAIMS_OFFICER");

    verify(auditRecorder)
        .record(eq(AuditEventType.CLAIM_UNASSIGNED), eq("CLAIM"), eq(CLAIM_ID), eq(CLAIM_ID), any(),
            anyMap());
  }

  @Test
  @DisplayName("unassign() with no active holder is a no-op without audit noise")
  void unassignWithoutHolderIsIdempotent() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim()));
    when(claimAssignmentPort.unassign(CLAIM_ID, "CLAIMS_OFFICER")).thenReturn(Optional.empty());

    claimAssignmentService.unassign(CLAIM_ID, "CLAIMS_OFFICER");

    verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), anyMap());
  }

  private static Claim claim() {
    return new Claim(
        CLAIM_ID,
        "CLM-20250310-ABCD1234",
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "POL-0001",
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "CAR_DAMAGE",
        ClaimStatus.UNDER_REVIEW,
        "Windscreen damaged",
        LocalDate.of(2025, 2, 1),
        NOW,
        NOW,
        List.of());
  }

  private static Actor actor(boolean enabled) {
    return new Actor(
        ACTOR_ID,
        "mario.rossi@example.com",
        "Mario Rossi",
        "mario.rossi@example.com",
        ActorType.INTERNAL,
        enabled,
        Set.of("CLAIMS_OFFICER"),
        NOW,
        NOW);
  }

  private static ClaimAssignment assignment(UUID actorId) {
    return new ClaimAssignment(
        UUID.randomUUID(), CLAIM_ID, actorId, "Mario Rossi", "CLAIMS_OFFICER", NOW, null, null);
  }
}
