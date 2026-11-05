package it.enterprise.claims.core.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.enterprise.claims.core.application.ClaimService;
import it.enterprise.claims.core.application.dto.AuditEventPageResponse;
import it.enterprise.claims.core.application.dto.AuditEventResponse;
import it.enterprise.claims.core.application.exception.ClaimNotFoundException;
import it.enterprise.claims.core.domain.model.AuditEventType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice of the audit endpoint: paging, filter and error mapping. */
@WebMvcTest(controllers = ClaimAuditEventController.class)
class ClaimAuditEventControllerTest {

  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2025-03-10T09:00:00Z");

  @Autowired private MockMvc mockMvc;

  @MockBean private ClaimService claimService;

  @Test
  @DisplayName("GET /api/v1/claims/{id}/audit-events returns the page envelope")
  void listReturnsPage() throws Exception {
    when(claimService.auditEvents(eq(CLAIM_ID), eq(null), any()))
        .thenReturn(new AuditEventPageResponse(List.of(eventResponse()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/api/v1/claims/{claimId}/audit-events", CLAIM_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].eventType").value("CLAIM_STATUS_CHANGED"))
        .andExpect(jsonPath("$.content[0].details.toStatus").value("APPROVED"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("GET /api/v1/claims/{id}/audit-events filters on the event type")
  void listFiltersByEventType() throws Exception {
    when(claimService.auditEvents(eq(CLAIM_ID), eq(AuditEventType.CLAIM_OPENED), any()))
        .thenReturn(new AuditEventPageResponse(List.of(), 0, 20, 0, 0));

    mockMvc
        .perform(
            get("/api/v1/claims/{claimId}/audit-events", CLAIM_ID)
                .param("eventType", "CLAIM_OPENED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @DisplayName("GET on an unknown claim returns 404")
  void listReturnsNotFound() throws Exception {
    when(claimService.auditEvents(eq(CLAIM_ID), eq(null), any()))
        .thenThrow(new ClaimNotFoundException(CLAIM_ID));

    mockMvc
        .perform(get("/api/v1/claims/{claimId}/audit-events", CLAIM_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Claim not found"));
  }

  private static AuditEventResponse eventResponse() {
    return new AuditEventResponse(
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        CLAIM_ID,
        null,
        AuditEventType.CLAIM_STATUS_CHANGED,
        "CLAIM",
        CLAIM_ID,
        "SUCCESS",
        NOW,
        null,
        Map.of("toStatus", "APPROVED"));
  }
}
