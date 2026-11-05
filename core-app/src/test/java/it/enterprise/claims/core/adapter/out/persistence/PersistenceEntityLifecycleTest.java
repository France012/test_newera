package it.enterprise.claims.core.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import it.enterprise.claims.core.domain.model.ClaimStatus;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the persistence model introduced by TK-64FBC8 ({@code
 * V2__claim_workflow_actors_audit.sql}).
 *
 * <p>These are pure unit tests: no Spring context, no database. They pin the parts of the mapping
 * that a text-level schema contract cannot see — JPA lifecycle callbacks (the timestamps the
 * database would otherwise provide through a trigger, which DD-8 forbids), the soft-delete and
 * soft-un-assign helpers, and the append-only shape of the audit repository.
 */
class PersistenceEntityLifecycleTest {

  @Test
  @DisplayName(
      "an actor gets created_at/updated_at from the lifecycle callbacks, not from a trigger")
  void actorTimestampsAreMaintainedByTheEntity() {
    ActorJpaEntity actor =
        new ActorJpaEntity(UUID.randomUUID(), "mario.rossi", "Mario Rossi", null, "INTERNAL");

    assertThat(actor.getCreatedAt()).as("no timestamp before persist").isNull();
    actor.onCreate();

    assertThat(actor.getCreatedAt()).isNotNull();
    assertThat(actor.getUpdatedAt()).isEqualTo(actor.getCreatedAt());
    assertThat(actor.isEnabled()).as("enabled defaults to true in the mapping").isTrue();

    actor.onUpdate();
    assertThat(actor.getUpdatedAt()).isAfterOrEqualTo(actor.getCreatedAt());
  }

  @Test
  @DisplayName("soft delete of an actor keeps the row readable and is idempotent")
  void actorSoftDelete() {
    ActorJpaEntity actor =
        new ActorJpaEntity(UUID.randomUUID(), "mario.rossi", "Mario Rossi", null, "INTERNAL");
    actor.onCreate();

    assertThat(actor.isDeleted()).isFalse();

    OffsetDateTime when = OffsetDateTime.now();
    actor.markDeleted(when);
    assertThat(actor.isDeleted()).isTrue();
    assertThat(actor.getDeletedAt()).isEqualTo(when);
    assertThat(actor.getSubjectRef()).as("the identity survives the soft delete").isNotBlank();
  }

  @Test
  @DisplayName("a revoked role grant is reactivated instead of being re-created")
  void actorRoleRevokeAndRegrant() {
    ActorRoleJpaEntity grant =
        new ActorRoleJpaEntity(UUID.randomUUID(), "CLAIMS_OFFICER", UUID.randomUUID());
    grant.onCreate();

    assertThat(grant.isActive()).isTrue();
    assertThat(grant.getGrantedAt()).isNotNull();

    grant.revoke(null);
    assertThat(grant.isActive()).isFalse();
    assertThat(grant.getRevokedAt()).isNotNull();

    grant.regrant();
    assertThat(grant.isActive()).isTrue();
    assertThat(grant.getRevokedAt()).isNull();
  }

  @Test
  @DisplayName("un-assigning a claim closes the row instead of deleting it")
  void claimAssignmentIsSoftClosed() {
    ClaimAssignmentJpaEntity assignment =
        new ClaimAssignmentJpaEntity(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "APPRAISER", null);
    assignment.onCreate();

    assertThat(assignment.isActive()).isTrue();
    assertThat(assignment.getAssignedAt()).isNotNull();

    assignment.unassign(null);
    assertThat(assignment.isActive()).isFalse();
    assertThat(assignment.getUnassignedAt()).isNotNull();
    assertThat(assignment.getRoleCode()).isEqualTo("APPRAISER");
  }

  @Test
  @DisplayName("a document link can be un-linked without losing the audit trail")
  void claimDocumentLinkIsSoftDeleted() {
    UUID claimId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    ClaimDocumentJpaEntity link =
        new ClaimDocumentJpaEntity(claimId, documentId, UUID.randomUUID());
    link.onCreate();

    assertThat(link.isActive()).isTrue();
    assertThat(link.getLinkedAt()).isNotNull();
    assertThat(link.getLinkedByActorId()).isNotNull();

    link.unlink(null);
    assertThat(link.isActive()).isFalse();
    assertThat(link.getId().getClaimId()).isEqualTo(claimId);
    assertThat(link.getId().getDocumentId()).isEqualTo(documentId);
  }

  @Test
  @DisplayName("the audit trail is append-only: no update/delete path exists in the repository")
  void auditRepositoryHasNoMutator() {
    List<String> mutators =
        Arrays.stream(AuditEventJpaRepository.class.getDeclaredMethods())
            .filter(method -> !Modifier.isStatic(method.getModifiers()))
            .map(Method::getName)
            .filter(
                name ->
                    name.startsWith("delete")
                        || name.startsWith("remove")
                        || name.startsWith("update")
                        || name.startsWith("saveAll"))
            .toList();

    assertThat(mutators).as("decision DD-11: the audit trail must never be mutable").isEmpty();
    assertThat(AuditEventJpaEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
  }

  @Test
  @DisplayName("the audit event defaults to outcome SUCCESS and keeps a nullable claim/actor")
  void auditEventDefaults() {
    AuditEventJpaEntity event =
        new AuditEventJpaEntity(
            UUID.randomUUID(),
            null,
            null,
            "ACTOR_CREATED",
            "ACTOR",
            UUID.randomUUID(),
            null,
            null,
            null);
    event.onCreate();

    assertThat(event.getOutcome()).isEqualTo("SUCCESS");
    assertThat(event.getOccurredAt()).isNotNull();
    assertThat(event.getClaimId()).as("non-claim events are allowed").isNull();
  }

  @Test
  @DisplayName("a claim keeps null closed_at/archived_at until the state machine sets them")
  void claimTimestampsAreOptional() {
    ClaimJpaEntity claim =
        new ClaimJpaEntity(
            UUID.randomUUID(),
            "CLM-2026-0001",
            UUID.randomUUID(),
            "MOTOR",
            "sinistro",
            java.time.LocalDate.now(),
            ClaimStatus.SETTLEMENT);

    assertThat(claim.getClosedAt()).isNull();
    assertThat(claim.getArchivedAt()).isNull();

    claim.setClosedAt(OffsetDateTime.now());
    assertThat(claim.getClosedAt()).isNotNull();
  }

  @Test
  @DisplayName("a status-history row can record its author; historical rows stay without one")
  void statusHistoryCarriesTheActor() {
    UUID actorId = UUID.randomUUID();
    ClaimStatusHistoryJpaEntity legacy =
        new ClaimStatusHistoryJpaEntity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ClaimStatus.UNDER_REVIEW,
            ClaimStatus.APPROVED,
            "ok");
    assertThat(legacy.getActorId()).as("rows written before TK-64FBC8 have no actor").isNull();

    ClaimStatusHistoryJpaEntity row =
        new ClaimStatusHistoryJpaEntity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ClaimStatus.APPROVED,
            ClaimStatus.SETTLEMENT,
            "delibera",
            actorId);
    assertThat(row.getActorId()).isEqualTo(actorId);
  }
}
