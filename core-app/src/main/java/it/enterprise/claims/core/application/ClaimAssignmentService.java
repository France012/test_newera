package it.enterprise.claims.core.application;

import it.enterprise.claims.core.application.dto.ClaimAssignmentRequest;
import it.enterprise.claims.core.application.dto.ClaimAssignmentResponse;
import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.InvalidAssignmentException;
import it.enterprise.claims.core.application.exception.UnknownRoleException;
import it.enterprise.claims.core.domain.model.Actor;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.model.ClaimAssignment;
import it.enterprise.claims.core.domain.port.ActorRepositoryPort;
import it.enterprise.claims.core.domain.port.ClaimAssignmentPort;
import it.enterprise.claims.core.domain.port.ClaimRepositoryPort;
import it.enterprise.claims.core.domain.port.RoleRepositoryPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the "attore + ruolo su una pratica" relation ({@code coreapp.claim_assignments}).
 *
 * <p>Assigning a claim is a re-assignment of a role, never a bare insert: the previous holder is
 * closed in the same transaction, so the API can never leave two active holders of the same role
 * and a caller does not have to know whether a holder already exists.
 *
 * <p>The partial unique index {@code uk_claim_assignments_active_role} is the second line of
 * defence: if two callers race, the loser gets a 409 instead of corrupting the data.
 */
@Service
public class ClaimAssignmentService {

  private static final String ENTITY_TYPE = "CLAIM";

  private final ClaimAssignmentPort claimAssignmentPort;
  private final ActorRepositoryPort actorRepository;
  private final RoleRepositoryPort roleRepository;
  private final ClaimRepositoryPort claimRepository;
  private final AuditRecorder auditRecorder;
  private final Clock clock;

  /**
   * @param claimAssignmentPort assignment persistence port
   * @param actorRepository actor lookup port
   * @param roleRepository role reference data port
   * @param claimRepository claim lookup port (existence of the claim is a precondition)
   * @param auditRecorder audit trail writer
   * @param clock clock used to timestamp the assignment (injectable in tests)
   */
  public ClaimAssignmentService(
      ClaimAssignmentPort claimAssignmentPort,
      ActorRepositoryPort actorRepository,
      RoleRepositoryPort roleRepository,
      ClaimRepositoryPort claimRepository,
      AuditRecorder auditRecorder,
      Clock clock) {
    this.claimAssignmentPort = claimAssignmentPort;
    this.actorRepository = actorRepository;
    this.roleRepository = roleRepository;
    this.claimRepository = claimRepository;
    this.auditRecorder = auditRecorder;
    this.clock = clock;
  }

  /**
   * @param claimId claim whose assignments are requested
   * @param activeOnly {@code true} to return only the current holders
   * @return the assignments, most recent first
   * @throws ClaimNotFoundException when the claim does not exist
   */
  @Transactional(readOnly = true)
  public List<ClaimAssignmentResponse> findAll(UUID claimId, boolean activeOnly) {
    requireClaim(claimId);
    return claimAssignmentPort.findByClaimId(claimId, activeOnly).stream()
        .map(ClaimAssignmentResponse::from)
        .toList();
  }

  /**
   * Makes the given actor the current holder of a role on a claim, closing the previous holder.
   *
   * @param claimId claim to assign
   * @param roleCode role to assign
   * @param request actor to assign
   * @return the new assignment
   * @throws ClaimNotFoundException when the claim does not exist
   * @throws UnknownRoleException when the role code is not part of the reference data
   * @throws ActorNotFoundException when the actor does not exist
   * @throws InvalidAssignmentException when the actor is disabled or a concurrent assignment won
   */
  @Transactional
  public ClaimAssignmentResponse assign(
      UUID claimId, String roleCode, ClaimAssignmentRequest request) {
    requireClaim(claimId);
    String code = roleCode == null ? "" : roleCode.trim();
    if (!roleRepository.existsByCode(code)) {
      throw new UnknownRoleException(code);
    }
    Actor actor =
        actorRepository
            .findById(request.actorId())
            .orElseThrow(() -> new ActorNotFoundException(request.actorId()));
    if (!actor.enabled()) {
      throw new InvalidAssignmentException(
          "Actor " + actor.id() + " is disabled and cannot be assigned to a claim");
    }

    UUID previousActorId =
        claimAssignmentPort.findActive(claimId, code).map(ClaimAssignment::actorId).orElse(null);

    ClaimAssignment assignment =
        new ClaimAssignment(
            UUID.randomUUID(),
            claimId,
            actor.id(),
            actor.displayName(),
            code,
            OffsetDateTime.now(clock),
            null,
            null);
    ClaimAssignment saved;
    try {
      saved = claimAssignmentPort.assign(assignment);
    } catch (DataIntegrityViolationException ex) {
      throw new InvalidAssignmentException(
          "Role " + code + " on claim " + claimId + " was assigned concurrently", ex);
    }

    auditRecorder.record(
        AuditEventType.CLAIM_ASSIGNED,
        ENTITY_TYPE,
        claimId,
        claimId,
        null,
        details(code, actor.id(), previousActorId));
    return ClaimAssignmentResponse.from(saved);
  }

  /**
   * Closes the active assignment of a role. Idempotent: closing nothing is not an error.
   *
   * @param claimId claim to update
   * @param roleCode role to close
   * @throws ClaimNotFoundException when the claim does not exist
   */
  @Transactional
  public void unassign(UUID claimId, String roleCode) {
    requireClaim(claimId);
    claimAssignmentPort
        .unassign(claimId, roleCode)
        .ifPresent(
            closed ->
                auditRecorder.record(
                    AuditEventType.CLAIM_UNASSIGNED,
                    ENTITY_TYPE,
                    claimId,
                    claimId,
                    null,
                    Map.of("roleCode", roleCode, "actorId", closed.actorId().toString())));
  }

  private void requireClaim(UUID claimId) {
    if (claimRepository.findById(claimId).isEmpty()) {
      throw new ClaimNotFoundException(claimId);
    }
  }

  private static Map<String, Object> details(String roleCode, UUID actorId, UUID previousActorId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("roleCode", roleCode);
    details.put("actorId", actorId.toString());
    if (previousActorId != null) {
      details.put("previousActorId", previousActorId.toString());
    }
    return details;
  }
}
