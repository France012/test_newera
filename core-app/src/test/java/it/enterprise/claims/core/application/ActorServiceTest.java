package it.enterprise.claims.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.enterprise.claims.core.application.dto.ActorCreateRequest;
import it.enterprise.claims.core.application.dto.ActorResponse;
import it.enterprise.claims.core.application.dto.ActorRolesUpdateRequest;
import it.enterprise.claims.core.application.dto.ActorUpdateRequest;
import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.DuplicateActorException;
import it.enterprise.claims.core.application.exception.UnknownRoleException;
import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.ActorType;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.port.ActorRepositoryPort;
import it.enterprise.claims.core.domain.port.RoleRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests of the actor use cases: duplicate subject, role vocabulary, audit trail. */
@ExtendWith(MockitoExtension.class)
class ActorServiceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2025-03-10T09:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2025-03-10T09:00:00Z");
  private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String SUBJECT = "mario.rossi@example.com";

  @Mock private ActorRepositoryPort actorRepository;
  @Mock private RoleRepositoryPort roleRepository;
  @Mock private AuditRecorder auditRecorder;

  private ActorService actorService;

  @BeforeEach
  void setUp() {
    actorService = new ActorService(actorRepository, roleRepository, auditRecorder, FIXED_CLOCK);
  }

  @Test
  @DisplayName("create() registers the actor and appends ACTOR_CREATED")
  void createRegistersActor() {
    when(actorRepository.existsBySubjectRef(SUBJECT)).thenReturn(false);
    when(roleRepository.existsByCode("CLAIMS_OFFICER")).thenReturn(true);
    when(actorRepository.create(any(Actor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ActorResponse response =
        actorService.create(
            new ActorCreateRequest(
                SUBJECT, "Mario Rossi", SUBJECT, ActorType.INTERNAL, Set.of("CLAIMS_OFFICER")));

    assertThat(response.subjectRef()).isEqualTo(SUBJECT);
    assertThat(response.roleCodes()).containsExactly("CLAIMS_OFFICER");
    assertThat(response.enabled()).isTrue();
    verify(auditRecorder)
        .record(eq(AuditEventType.ACTOR_CREATED), eq("ACTOR"), any(), any(), any(), anyMap());
  }

  @Test
  @DisplayName("create() rejects an already registered subject with a conflict")
  void createRejectsDuplicateSubject() {
    when(actorRepository.existsBySubjectRef(SUBJECT)).thenReturn(true);

    assertThatThrownBy(
            () ->
                actorService.create(
                    new ActorCreateRequest(SUBJECT, "Mario Rossi", null, ActorType.INTERNAL, null)))
        .isInstanceOf(DuplicateActorException.class)
        .hasMessageContaining(SUBJECT);
    verify(actorRepository, never()).create(any());
  }

  @Test
  @DisplayName("create() rejects a role code outside the reference data")
  void createRejectsUnknownRole() {
    when(actorRepository.existsBySubjectRef(SUBJECT)).thenReturn(false);
    when(roleRepository.existsByCode("NOPE")).thenReturn(false);

    assertThatThrownBy(
            () ->
                actorService.create(
                    new ActorCreateRequest(
                        SUBJECT, "Mario Rossi", null, ActorType.INTERNAL, Set.of("NOPE"))))
        .isInstanceOf(UnknownRoleException.class)
        .hasMessageContaining("NOPE");
    verify(actorRepository, never()).create(any());
  }

  @Test
  @DisplayName("update() patches only the provided fields")
  void updatePatchesProvidedFields() {
    when(actorRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor()));
    when(actorRepository.update(any(Actor.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ActorResponse response =
        actorService.update(ACTOR_ID, new ActorUpdateRequest("Mario Rossi (ferie)", null, false));

    assertThat(response.displayName()).isEqualTo("Mario Rossi (ferie)");
    assertThat(response.email()).isEqualTo(SUBJECT);
    assertThat(response.enabled()).isFalse();
  }

  @Test
  @DisplayName("update() on an unknown actor is a not-found")
  void updateRejectsUnknownActor() {
    when(actorRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> actorService.update(ACTOR_ID, new ActorUpdateRequest("x", null, null)))
        .isInstanceOf(ActorNotFoundException.class);
  }

  @Test
  @DisplayName("replaceRoles() delegates the grant/revoke protocol and appends the audit event")
  void replaceRolesDelegatesToRepository() {
    when(actorRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor()));
    when(roleRepository.existsByCode("APPRAISER")).thenReturn(true);
    when(actorRepository.replaceRoles(eq(ACTOR_ID), eq(Set.of("APPRAISER")), eq(null)))
        .thenReturn(Set.of("APPRAISER"));

    ActorResponse response =
        actorService.replaceRoles(ACTOR_ID, new ActorRolesUpdateRequest(Set.of("APPRAISER")));

    assertThat(response.roleCodes()).containsExactly("APPRAISER");
    verify(auditRecorder)
        .record(
            eq(AuditEventType.ACTOR_ROLES_CHANGED),
            eq("ACTOR"),
            eq(ACTOR_ID),
            any(),
            any(),
            anyMap());
  }

  private static Actor actor() {
    return new Actor(
        ACTOR_ID,
        SUBJECT,
        "Mario Rossi",
        SUBJECT,
        ActorType.INTERNAL,
        true,
        Set.of("CLAIMS_OFFICER"),
        NOW,
        NOW);
  }
}
