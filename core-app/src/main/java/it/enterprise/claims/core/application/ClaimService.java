package it.enterprise.claims.core.application;

import it.enterprise.claims.core.application.dto.ClaimCreateRequest;
import it.enterprise.claims.core.application.dto.ClaimPageResponse;
import it.enterprise.claims.core.application.dto.ClaimResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusHistoryResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusUpdateRequest;
import it.enterprise.claims.core.application.dto.DocumentIdsRequest;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.InvalidClaimRequestException;
import it.enterprise.claims.core.application.exception.InvalidClaimStatusTransitionException;
import it.enterprise.claims.core.application.exception.PolicyNotFoundException;
import it.enterprise.claims.core.domain.model.Claim;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import it.enterprise.claims.core.domain.model.Policy;
import it.enterprise.claims.core.domain.port.ClaimDocumentPort;
import it.enterprise.claims.core.domain.port.ClaimRepositoryPort;
import it.enterprise.claims.core.domain.port.PolicyRepositoryPort;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
 *       caller gets a 409;
 *   <li>every status change is recorded in the audit trail by the repository adapter.
 * </ul>
 */
@Service
public class ClaimService {

  private static final DateTimeFormatter CLAIM_NUMBER_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final int MAX_CLAIM_NUMBER_ATTEMPTS = 3;
  private static final String CLAIM_NUMBER_PREFIX = "CLM-";

  private final ClaimRepositoryPort claimRepository;
  private final PolicyRepositoryPort policyRepository;
  private final ClaimDocumentPort claimDocumentPort;
  private final Clock clock;

  /**
   * @param claimRepository claim persistence port
   * @param policyRepository policy lookup port
   * @param claimDocumentPort link-table port
   * @param clock clock used to timestamp idempotency-sensitive values (injectable in tests)
   */
  public ClaimService(
      ClaimRepositoryPort claimRepository,
      PolicyRepositoryPort policyRepository,
      ClaimDocumentPort claimDocumentPort,
      Clock clock) {
    this.claimRepository = claimRepository;
    this.policyRepository = policyRepository;
    this.claimDocumentPort = claimDocumentPort;
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

    return ClaimResponse.from(claimRepository.create(claim));
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
   * @param request requested target state and optional note
   * @return the updated claim
   * @throws InvalidClaimStatusTransitionException when the transition is not allowed (HTTP 409)
   */
  @Transactional
  public ClaimResponse changeStatus(UUID claimId, ClaimStatusUpdateRequest request) {
    Claim claim = load(claimId);
    if (!claim.canTransitionTo(request.status())) {
      throw new InvalidClaimStatusTransitionException(claimId, claim.status(), request.status());
    }
    return ClaimResponse.from(
        claimRepository.updateStatus(claim, request.status(), request.note()));
  }

  /**
   * @param claimId claim whose audit trail is requested
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
   * Attaches document-service ids to a claim. Idempotent: already linked ids are ignored.
   *
   * @param claimId claim to update
   * @param request documents to attach
   * @return the claim with the complete list of attached documents
   */
  @Transactional
  public ClaimResponse attachDocuments(UUID claimId, DocumentIdsRequest request) {
    Claim claim = load(claimId);
    List<UUID> documentIds = claimDocumentPort.link(claimId, request.documentIds());
    return ClaimResponse.from(claim.withDocumentIds(documentIds));
  }

  private Claim load(UUID claimId) {
    return claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
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
