package it.enterprise.claims.core.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.enterprise.claims.core.application.ClaimService;
import it.enterprise.claims.core.application.dto.ClaimCreateRequest;
import it.enterprise.claims.core.application.dto.ClaimPageResponse;
import it.enterprise.claims.core.application.dto.ClaimResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusUpdateRequest;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.InvalidClaimStatusTransitionException;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice of the claim controller: HTTP contract and error mapping, no database involved. */
@WebMvcTest(controllers = ClaimController.class)
class ClaimControllerTest {

  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final OffsetDateTime OPENED_AT = OffsetDateTime.parse("2025-03-10T09:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private ClaimService claimService;

  @Test
  @DisplayName("GET /api/v1/claims returns the page envelope")
  void listReturnsPage() throws Exception {
    when(claimService.findAll(eq(null), any()))
        .thenReturn(new ClaimPageResponse(List.of(claimResponse()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/api/v1/claims"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content[0].claimNumber").value("CLM-20250310-ABCD1234"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("GET /api/v1/claims returns 400 when the status filter is not a valid enum value")
  void listRejectsUnknownStatus() throws Exception {
    mockMvc
        .perform(get("/api/v1/claims").param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @DisplayName("GET /api/v1/claims/{id} returns 404 as application/problem+json")
  void getReturnsNotFound() throws Exception {
    when(claimService.findById(CLAIM_ID)).thenThrow(new ClaimNotFoundException(CLAIM_ID));

    mockMvc
        .perform(get("/api/v1/claims/{id}", CLAIM_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Claim not found"))
        .andExpect(jsonPath("$.instance").value("/api/v1/claims/" + CLAIM_ID));
  }

  @Test
  @DisplayName("POST /api/v1/claims returns 201 with a Location header")
  void createReturnsCreatedAndLocation() throws Exception {
    when(claimService.open(any(ClaimCreateRequest.class))).thenReturn(claimResponse());

    String body =
        objectMapper.writeValueAsString(
            new ClaimCreateRequest(
                "POL-0001",
                CUSTOMER_ID,
                "CAR_DAMAGE",
                "Windscreen damaged",
                LocalDate.of(2025, 2, 1)));

    mockMvc
        .perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/claims/" + CLAIM_ID))
        .andExpect(jsonPath("$.id").value(CLAIM_ID.toString()));
  }

  @Test
  @DisplayName("POST /api/v1/claims returns 400 with field errors when the body is invalid")
  void createReturnsValidationProblem() throws Exception {
    String body =
        objectMapper.writeValueAsString(
            new ClaimCreateRequest(null, null, "", "x", LocalDate.of(2030, 1, 1)));

    mockMvc
        .perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.errors.policyNumber").exists())
        .andExpect(jsonPath("$.errors.claimType").exists())
        .andExpect(jsonPath("$.errors.incidentDate").exists());
  }

  @Test
  @DisplayName("PATCH /api/v1/claims/{id}/status returns 409 listing the allowed targets")
  void changeStatusReturnsConflict() throws Exception {
    when(claimService.changeStatus(eq(CLAIM_ID), any(ClaimStatusUpdateRequest.class)))
        .thenThrow(
            new InvalidClaimStatusTransitionException(
                CLAIM_ID, ClaimStatus.CLOSED, ClaimStatus.SUBMITTED));

    String body =
        objectMapper.writeValueAsString(new ClaimStatusUpdateRequest(ClaimStatus.SUBMITTED, null));

    mockMvc
        .perform(
            patch("/api/v1/claims/{id}/status", CLAIM_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Illegal claim status transition"))
        .andExpect(jsonPath("$.fromStatus").value("CLOSED"))
        .andExpect(jsonPath("$.requestedStatus").value("SUBMITTED"));
  }

  @Test
  @DisplayName("PATCH with an unknown status value returns 400, not 500")
  void changeStatusRejectsUnknownEnumValue() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/claims/{id}/status", CLAIM_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"NOT_A_STATUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Malformed request body"));
  }

  @Test
  @DisplayName("GET /api/v1/claims/{id} with a non-UUID id returns 400")
  void getRejectsMalformedId() throws Exception {
    mockMvc
        .perform(get("/api/v1/claims/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request parameter"));
  }

  private static ClaimResponse claimResponse() {
    return new ClaimResponse(
        CLAIM_ID,
        "CLM-20250310-ABCD1234",
        "POL-0001",
        CUSTOMER_ID,
        "CAR_DAMAGE",
        ClaimStatus.DRAFT,
        "Windscreen damaged",
        LocalDate.of(2025, 2, 1),
        OPENED_AT,
        OPENED_AT,
        List.of(),
        null,
        null);
  }
}
