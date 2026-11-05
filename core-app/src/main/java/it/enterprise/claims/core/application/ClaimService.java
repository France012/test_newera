package it.enterprise.claims.core.application;

import it.enterprise.claims.core.application.dto.AuditEventPageResponse;
import it.enterprise.claims.core.application.dto.ClaimCreateRequest;
import it.enterprise.claims.core.application.dto.ClaimDocumentLinkResponse;
import it.enterprise.claims.core.application.dto.ClaimPageResponse;
import it.enterprise.claims.core.application.dto.ClaimResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusHistoryResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusUpdateRequest;
import it.enterprise.claims.core.application.dto.DocumentIdsRequest;
import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.InvalidClaimRequestException;
import it.enterprise.claims.core.application.exception.InvalidClaimStatusTransitionException;
import it.enterprise.claims.core.application.exception.PolicyNotFoundException;
import it.enterprise.claims.core.domain.model.AuditEventType;
import it.enterprise.claims.core.domain.model.Claim;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import it.enterprise.claims.core.domain.model.Policy;
import it.enterprise.claims.core.domain.port.ActorRepositoryPort;
import it.enterprise.claims.core.domain.port.AuditEventPort;
import it.enterprise.claims.core.domain.port.ClaimDocumentPort;
import it.enterprise.claims.core.domain.port.ClaimRepositoryPort;
import it.enterprise.claims.core.domain.port.PolicyRepositoryPort;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the claim aggregate.
 *
 * <p>All business rules of the blueprint live here, not in the controller and not in the entity:
 *
 * <ul>
 *   <li>a claim can only be opened on an existing policy, and the incident must fall inside the
 *       policy validity period;
 *   <li>opening a claim always starts in {@link ClaimStatus#DRAFT};
 *   <li>every status change must be allowed by the {@link ClaimStatus} state machine, otherwise the
 *       caller gets a 409 (the composite foreign key {@code fk_claim_status_history_transition} is
 *       the second line of defence, at database level);
 *   <li>entering {@code CLOSED} stamps {@code closedAt}, entering {@code ARCHIVED} stamps {@code
 *       archivedAt};
 *   <li>every state change, document link and un-link appends one row to the audit trail, in the
 *       same transaction as the change itself.
 * </ul>
 */
@Service
public class ClaimService {

  private static final DateTimeFormatter CLAIM_NUMBER_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final int MAX_CLAIM_NUMBER_ATTEMPTS = 3;
  private static final String CLAIM_NUMBER_PREFIX = "CLM-";
  private static final String CLAIM_ENTITY = "CLAIM";

  private final ClaimRepositoryPort claimRepository;
  private final PolicyRepositoryPort policyRepository;
  private final ClaimDocumentPort claimDocumentPort;
  private final ActorRepositoryPort actorRepository;
  private final AuditEventPort auditEventPort;
  private final AuditRecorder auditRecorder;
  private final Clock clock;

  /**
   * @param claimRepository claim persistence port
   * @param policyRepository policy lookup port
   * @param claimDocumentPort link-table port
   * @param actorRepository actor lookup port (validates the asserted {@code actorId})
   * @param auditEventPort read side of the audit trail
   * @param auditRecorder audit trail writer
   * @param clock clock used to timestamp idempotency-sensitive values (injectable in tests)
   */
  public ClaimService(
      ClaimRepositoryPort claimRepository,
      PolicyRepositoryPort policyRepository,
      ClaimDocumentPort claimDocumentPort,
      ActorRepositoryPort actorRepository,
      AuditEventPort auditEventPort,
      AuditRecorder auditRecorder,
      Clock clock) {
    this.claimRepository = claimRepository;
    this.policyRepository = policyRepository;
    this.claimDocumentPort = claimDocumentPort;
    this.actorRepository = actorRepository;
    this.auditEventPort = auditEventPort;
    this.auditRecorder = auditRecorder;
    this.clock = clock;
  }

  /**
   * Opens a new claim in {@code DRAFT}.
   *
   * @param request validated request payload
   * @return the created claim
   * @throws PolicyNotFoundException when {@code policyNumber} does not exist
   * @throws InvalidClaimRequestException when the incident date is outside the policy cover or the
   *     declared {@code customerId} does not own the policy
   */
  @Transactional
  public ClaimResponse open(ClaimCreateRequest request) {
    Policy policy =
        policyRepository
            .findByPolicyNumber(request.policyNumber())
            .orElseThrow(() -> new PolicyNotFoundException(request.policyNumber()));

    if (request.customerId() != null && !policy.customerId().equals(request.customerId())) {
      throw new InvalidClaimRequestException(
          "customerId "
              + request.customerId()
              + " is not the owner of policy "
              + policy.policyNumber());
    }
    if (!policy.covers(request.incidentDate())) {
      throw new InvalidClaimRequestException(
          "incidentDate "
              + request.incidentDate()
              + " is outside the cover period of policy "
              + policy.policyNumber());
    }

    Claim claim =
        Claim.opened(
            UUID.randomUUID(),
            nextClaimNumber(),
            policy.id(),
            policy.policyNumber(),
            policy.customerId(),
            request.claimType(),
            request.description(),
            request.incidentDate(),
            OffsetDateTime.now(clock));

    Claim created = claimRepository.create(claim);
    auditRecorder.record(
        AuditEventType.CLAIM_OPENED,
        CLAIM_ENTITY,
        created.id(),
        created.id(),
        null,
        Map.of("claimNumber", created.claimNumber(), "status", created.status().name()));
    return ClaimResponse.from(created);
  }

  /**
   * @param claimId identifier to read
   * @return the claim
   * @throws ClaimNotFoundException when no claim has that id
   */
  @Transactional(readOnly = true)
  public ClaimResponse findById(UUID claimId) {
    return ClaimResponse.from(load(claimId));
  }

  /**
   * @param status optional status filter, {@code null} means all statuses
   * @param pageable page request
   * @return the matching page of claims
   */
  @Transactional(readOnly = true)
  public ClaimPageResponse findAll(ClaimStatus status, Pageable pageable) {
    return ClaimPageResponse.from(claimRepository.findPage(status, pageable));
  }

  /**
   * Moves a claim to another state, enforcing the state machine.
   *
   * @param claimId claim to update
   * @param request requested target state, optional note and optional asserted actor
   * @return the updated claim
   * @throws InvalidClaimStatusTransitionException when the transition is not allowed (HTTP 409)
   * @throws ActorNotFoundException when the asserted {@code actorId} does not exist (HTTP 404)
   */
  @Transactional
  public ClaimResponse changeStatus(UUID claimId, ClaimStatusUpdateRequest request) {
    Claim claim = load(claimId);
    if (!claim.canTransitionTo(request.status())) {
      throw new InvalidClaimStatusTransitionException(claimId, claim.status(), request.status());
    }
    requireActor(request.actorId());

    ClaimStatus previous = claim.status();
    Claim updated =
        claimRepository.updateStatus(claim, request.status(), request.note(), request.actorId());

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("fromStatus", previous.name());
    details.put("toStatus", request.status().name());
    if (request.note() != null) {
      details.put("note", request.note());
    }
    if (updated.closedAt() != null) {
      details.put("closedAt", updated.closedAt().toString());
    }
    if (updated.archivedAt() != null) {
      details.put("archivedAt", updated.archivedAt().toString());
    }
    auditRecorder.record(
        AuditEventType.CLAIM_STATUS_CHANGED,
        CLAIM_ENTITY,
        claimId,
        claimId,
        request.actorId(),
        details);
    return ClaimResponse.from(updated);
  }

  /**
   * @param claimId claim whose history is requested
   * @return the transitions in chronological order
   * @throws ClaimNotFoundException when no claim has that id
   */
  @Transactional(readOnly = true)
  public List<ClaimStatusHistoryResponse> statusHistory(UUID claimId) {
    load(claimId);
    return claimRepository.findStatusHistory(claimId).stream()
        .map(ClaimStatusHistoryResponse::from)
        .toList();
  }

  /**
   * @param claimId claim whose audit trail is requested
   * @param eventType optional filter on the business event type, may be {@code null}
   * @param pageable page request, newest first
   * @return one page of audit events
   * @throws ClaimNotFoundException when no claim has that id
   */
  @Transactional(readOnly = true)
  public AuditEventPageResponse auditEvents(
      UUID claimId, AuditEventType eventType, Pageable pageable) {
    load(claimId);
    return AuditEventPageResponse.from(auditEventPort.findByClaimId(claimId, eventType, pageable));
  }

  /**
   * Attaches document-service ids to a claim. Idempotent: already linked ids are ignored.
   *
   * @param claimId claim to update
   * @param request documents to attach
   * @return the claim with the complete list of attached documents
   */
  @Transactional
  public ClaimResponse attachDocuments(UUID claimId, DocumentIdsRequest request) {
    Claim claim = load(claimId);
    Set<UUID> before = new LinkedHashSet<>(claim.documentIds());
    List<UUID> documentIds = claimDocumentPort.link(claimId, request.documentIds());
    for (UUID documentId : documentIds) {
      if (!before.contains(documentId)) {
        auditRecorder.record(
            AuditEventType.CLAIM_DOCUMENT_LINKED,
            CLAIM_ENTITY,
            claimId,
            claimId,
            null,
            Map.of("documentId", documentId.toString()));
      }
    }
    return ClaimResponse.from(claim.withDocumentIds(documentIds));
  }

  /**
   * @param claimId claim whose document links are requested
   * @param includeUnlinked {@code true} to include the softly detached documents
   * @return the links, oldest first
   * @throws ClaimNotFoundException when no claim has that id
   */
  @Transactional(readOnly = true)
  public List<ClaimDocumentLinkResponse> findDocumentLinks(UUID claimId, boolean includeUnlinked) {
    load(claimId);
    return claimRepository.findDocumentLinks(claimId).stream()
        .filter(link -> includeUnlinked || link.isActive())
        .map(ClaimDocumentLinkResponse::from)
        .toList();
  }

  /**
   * Softly detaches a document from a claim.
   *
   * @param claimId claim to update
   * @param documentId document to detach
   * @return the closed link
   * @throws ClaimNotFoundException when no claim has that id
   */
  @Transactional
  public ClaimDocumentLinkResponse unlinkDocument(UUID claimId, UUID documentId) {
    load(claimId);
    ClaimDocumentLinkResponse response =
        ClaimDocumentLinkResponse.from(claimRepository.unlinkDocument(claimId, documentId));
    auditRecorder.record(
        AuditEventType.CLAIM_DOCUMENT_UNLINKED,
        CLAIM_ENTITY,
        claimId,
        claimId,
        null,
        Map.of("documentId", documentId.toString()));
    return response;
  }

  private Claim load(UUID claimId) {
    return claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
  }

  private void requireActor(UUID actorId) {
    if (actorId != null && actorRepository.findById(actorId).isEmpty()) {
      throw new ActorNotFoundException(actorId);
    }
  }

  /**
   * Generates a unique, human readable claim number: {@code CLM-yyyyMMdd-XXXXXXXX}.
   *
   * <p>The business key is generated in the application (not by a database sequence) because it is
   * needed before the insert, and it is checked against the unique constraint to keep the retry
   * explicit instead of relying on a constraint violation.
   */
  private String nextClaimNumber() {
    for (int attempt = 0; attempt < MAX_CLAIM_NUMBER_ATTEMPTS; attempt++) {
      String candidate =
          CLAIM_NUMBER_PREFIX
              + LocalDate.now(clock).format(CLAIM_NUMBER_DATE)
              + "-"
              + randomSuffix();
      if (!claimRepository.existsByClaimNumber(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Could not generate a unique claim number after "
            + MAX_CLAIM_NUMBER_ATTEMPTS
            + " attempts");
  }

  private static String randomSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }
}
