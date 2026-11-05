package it.enterprise.claims.core.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.enterprise.claims.core.application.ClaimAssignmentService;
import it.enterprise.claims.core.application.dto.ClaimAssignmentRequest;
import it.enterprise.claims.core.application.dto.ClaimAssignmentResponse;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.application.exception.InvalidAssignmentException;
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

/** Web slice of the assignment controller: PUT-as-replacement semantics and error mapping. */
@WebMvcTest(controllers = ClaimAssignmentController.class)
class ClaimAssignmentControllerTest {

  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2025-03-10T09:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private ClaimAssignmentService claimAssignmentService;

  @Test
  @DisplayName("GET /api/v1/claims/{id}/assignments lists the current holders")
  void listReturnsAssignments() throws Exception {
    when(claimAssignmentService.findAll(CLAIM_ID, true)).thenReturn(List.of(assignmentResponse()));

    mockMvc
        .perform(get("/api/v1/claims/{claimId}/assignments", CLAIM_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].roleCode").value("CLAIMS_OFFICER"))
        .andExpect(jsonPath("$[0].actorDisplayName").value("Mario Rossi"));
  }

  @Test
  @DisplayName("PUT /api/v1/claims/{id}/assignments/{roleCode} returns the new assignment")
  void assignReturnsAssignment() throws Exception {
    when(claimAssignmentService.assign(
            eq(CLAIM_ID), eq("CLAIMS_OFFICER"), any(ClaimAssignmentRequest.class)))
        .thenReturn(assignmentResponse());

    String body = objectMapper.writeValueAsString(new ClaimAssignmentRequest(ACTOR_ID));

    mockMvc
        .perform(
            put("/api/v1/claims/{claimId}/assignments/{roleCode}", CLAIM_ID, "CLAIMS_OFFICER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actorId").value(ACTOR_ID.toString()));
  }

  @Test
  @DisplayName("PUT with a disabled actor returns 409")
  void assignReturnsConflictForDisabledActor() throws Exception {
    when(claimAssignmentService.assign(
            eq(CLAIM_ID), eq("CLAIMS_OFFICER"), any(ClaimAssignmentRequest.class)))
        .thenThrow(new InvalidAssignmentException("Actor is disabled"));

    String body = objectMapper.writeValueAsString(new ClaimAssignmentRequest(ACTOR_ID));

    mockMvc
        .perform(
            put("/api/v1/claims/{claimId}/assignments/{roleCode}", CLAIM_ID, "CLAIMS_OFFICER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid assignment"));
  }

  @Test
  @DisplayName("GET on an unknown claim returns 404")
  void listReturnsNotFound() throws Exception {
    when(claimAssignmentService.findAll(CLAIM_ID, true))
        .thenThrow(new ClaimNotFoundException(CLAIM_ID));

    mockMvc
        .perform(get("/api/v1/claims/{claimId}/assignments", CLAIM_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Claim not found"));
  }

  @Test
  @DisplayName("DELETE /api/v1/claims/{id}/assignments/{roleCode} returns 204")
  void unassignReturnsNoContent() throws Exception {
    doNothing().when(claimAssignmentService).unassign(CLAIM_ID, "CLAIMS_OFFICER");

    mockMvc
        .perform(
            delete("/api/v1/claims/{claimId}/assignments/{roleCode}", CLAIM_ID, "CLAIMS_OFFICER"))
        .andExpect(status().isNoContent());
  }

  private static ClaimAssignmentResponse assignmentResponse() {
    return new ClaimAssignmentResponse(
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        CLAIM_ID,
        ACTOR_ID,
        "Mario Rossi",
        "CLAIMS_OFFICER",
        NOW,
        null,
        null);
  }
}
