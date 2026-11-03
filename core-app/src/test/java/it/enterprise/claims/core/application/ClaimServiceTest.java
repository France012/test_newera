package it.enterprise.claims.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.enterprise.claims.core.application.dto.ClaimCreateRequest;
import it.enterprise.claims.core.application.dto.ClaimResponse;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of the claim use cases: the state machine, the ownership/cover rules and the audit
 * trail trigger. Persistence is mocked, so no database is needed.
 */
@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2025-03-10T09:00:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime OPENED_AT = OffsetDateTime.parse("2025-03-10T09:00:00Z");

  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID POLICY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String POLICY_NUMBER = "POL-0001";

  @Mock private ClaimRepositoryPort claimRepository;
  @Mock private PolicyRepositoryPort policyRepository;
  @Mock private ClaimDocumentPort claimDocumentPort;

  private ClaimService claimService;

  @BeforeEach
  void setUp() {
    claimService =
        new ClaimService(claimRepository, policyRepository, claimDocumentPort, FIXED_CLOCK);
  }

  @Test
  @DisplayName("open() creates a DRAFT claim with a generated business key and the policy owner")
  void openCreatesDraftClaim() {
    when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy()));
    when(claimRepository.existsByClaimNumber(any())).thenReturn(false);
    when(claimRepository.create(any(Claim.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ClaimResponse response =
        claimService.open(createRequest(CUSTOMER_ID, LocalDate.of(2025, 2, 1)));

    assertThat(response.status()).isEqualTo(ClaimStatus.DRAFT);
    assertThat(response.claimNumber()).startsWith("CLM-20250310-").hasSize(21);
    assertThat(response.policyNumber()).isEqualTo(POLICY_NUMBER);
    assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
    assertThat(response.documentIds()).isEmpty();
  }

  @Test
  @DisplayName("open() rejects a policy that does not exist")
  void openRejectsUnknownPolicy() {
    when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> claimService.open(createRequest(null, LocalDate.of(2025, 2, 1))))
        .isInstanceOf(PolicyNotFoundException.class)
        .hasMessageContaining(POLICY_NUMBER);
    verify(claimRepository, never()).create(any());
  }

  @Test
  @DisplayName("open() rejects an incident date outside the cover period")
  void openRejectsIncidentOutsideCover() {
    when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy()));

    assertThatThrownBy(() -> claimService.open(createRequest(null, LocalDate.of(2024, 6, 1))))
        .isInstanceOf(InvalidClaimRequestException.class)
        .hasMessageContaining("outside the cover period");
    verify(claimRepository, never()).create(any());
  }

  @Test
  @DisplayName("open() rejects a customerId that does not own the policy")
  void openRejectsCustomerMismatch() {
    when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(Optional.of(policy()));

    UUID otherCustomer = UUID.fromString("44444444-4444-4444-8444-444444444444");
    assertThatThrownBy(
            () -> claimService.open(createRequest(otherCustomer, LocalDate.of(2025, 2, 1))))
        .isInstanceOf(InvalidClaimRequestException.class)
        .hasMessageContaining("not the owner");
  }

  @Test
  @DisplayName("changeStatus() applies an allowed transition")
  void changeStatusAppliesAllowedTransition() {
    Claim underReview = claim(ClaimStatus.UNDER_REVIEW);
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(underReview));
    when(claimRepository.updateStatus(underReview, ClaimStatus.APPROVED, "documents complete"))
        .thenReturn(underReview.withStatus(ClaimStatus.APPROVED, OPENED_AT));

    ClaimResponse response =
        claimService.changeStatus(
            CLAIM_ID, new ClaimStatusUpdateRequest(ClaimStatus.APPROVED, "documents complete"));

    assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
  }

  @Test
  @DisplayName("changeStatus() rejects an illegal transition without touching persistence")
  void changeStatusRejectsIllegalTransition() {
    Claim closed = claim(ClaimStatus.CLOSED);
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(closed));

    assertThatThrownBy(
            () ->
                claimService.changeStatus(
                    CLAIM_ID, new ClaimStatusUpdateRequest(ClaimStatus.SUBMITTED, null)))
        .isInstanceOf(InvalidClaimStatusTransitionException.class)
        .hasMessageContaining("CLOSED -> SUBMITTED");

    verify(claimRepository, never()).updateStatus(any(), any(), any());
  }

  @Test
  @DisplayName("changeStatus() on an unknown claim is a not-found, not a conflict")
  void changeStatusRejectsUnknownClaim() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                claimService.changeStatus(
                    CLAIM_ID, new ClaimStatusUpdateRequest(ClaimStatus.SUBMITTED, null)))
        .isInstanceOf(ClaimNotFoundException.class);
  }

  @Test
  @DisplayName("attachDocuments() returns the complete set of attached document ids")
  void attachDocumentsReturnsAllIds() {
    UUID first = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID second = UUID.fromString("66666666-6666-4666-8666-666666666666");
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim(ClaimStatus.DRAFT)));
    when(claimDocumentPort.link(CLAIM_ID, List.of(second))).thenReturn(List.of(first, second));

    ClaimResponse response =
        claimService.attachDocuments(CLAIM_ID, new DocumentIdsRequest(List.of(second)));

    assertThat(response.documentIds()).containsExactly(first, second);
    verify(claimDocumentPort).link(eq(CLAIM_ID), eq(List.of(second)));
  }

  @Test
  @DisplayName("statusHistory() fails with not-found before hitting the audit query")
  void statusHistoryRejectsUnknownClaim() {
    when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> claimService.statusHistory(CLAIM_ID))
        .isInstanceOf(ClaimNotFoundException.class);
    verify(claimRepository, never()).findStatusHistory(any());
  }

  private static ClaimCreateRequest createRequest(UUID customerId, LocalDate incidentDate) {
    return new ClaimCreateRequest(
        POLICY_NUMBER, customerId, "CAR_DAMAGE", "Windscreen damaged", incidentDate);
  }

  private static Policy policy() {
    return new Policy(
        POLICY_ID,
        POLICY_NUMBER,
        CUSTOMER_ID,
        "MOTOR",
        LocalDate.of(2025, 1, 1),
        LocalDate.of(2025, 12, 31),
        new BigDecimal("450.00"));
  }

  private static Claim claim(ClaimStatus status) {
    return new Claim(
        CLAIM_ID,
        "CLM-20250310-ABCD1234",
        POLICY_ID,
        POLICY_NUMBER,
        CUSTOMER_ID,
        "CAR_DAMAGE",
        status,
        "Windscreen damaged",
        LocalDate.of(2025, 2, 1),
        OPENED_AT,
        OPENED_AT,
        List.of());
  }
}
