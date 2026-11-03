package it.enterprise.claims.bff.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.enterprise.claims.bff.client.ClaimPagePayload;
import it.enterprise.claims.bff.client.ClaimsClient;
import it.enterprise.claims.bff.client.DocumentsClient;
import it.enterprise.claims.bff.config.CorsConfig;
import it.enterprise.claims.bff.config.SecurityConfig;
import it.enterprise.claims.bff.web.dto.ClaimSummaryResponse;
import it.enterprise.claims.bff.web.dto.DocumentSummaryResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Web slice of the BFF claim controller.
 *
 * <p>The real {@link SecurityConfig} and {@link CorsConfig} are imported so the security chain and
 * the CORS bean are part of the test; the downstream clients are mocked, so no service has to run.
 */
@WebMvcTest(controllers = BffClaimController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class BffClaimControllerTest {

  private static final UUID CLAIM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Autowired private MockMvc mockMvc;

  @MockBean private ClaimsClient claimsClient;
  @MockBean private DocumentsClient documentsClient;

  @Test
  @DisplayName("GET /bff/v1/claims forwards the claim list")
  void listReturnsClaims() throws Exception {
    when(claimsClient.findClaims(eq(null), eq(0), eq(20)))
        .thenReturn(new ClaimPagePayload(List.of(claimSummary()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/bff/v1/claims"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].claimNumber").value("CLM-20250310-ABCD1234"))
        .andExpect(jsonPath("$[0].status").value("DRAFT"));
  }

  @Test
  @DisplayName("GET /bff/v1/claims/{id} aggregates claim and documents")
  void detailAggregatesDocuments() throws Exception {
    when(claimsClient.findClaim(CLAIM_ID)).thenReturn(claimSummary());
    when(documentsClient.findByClaimId(CLAIM_ID)).thenReturn(List.of(documentSummary()));

    mockMvc
        .perform(get("/bff/v1/claims/{id}", CLAIM_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claim.id").value(CLAIM_ID.toString()))
        .andExpect(jsonPath("$.documents[0].fileName").value("policy.pdf"));
  }

  @Test
  @DisplayName("GET /bff/v1/claims/{id} maps a downstream 404 to 404")
  void detailMapsDownstreamNotFound() throws Exception {
    when(claimsClient.findClaim(CLAIM_ID))
        .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

    mockMvc
        .perform(get("/bff/v1/claims/{id}", CLAIM_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Resource not found"));
  }

  @Test
  @DisplayName("GET /bff/v1/claims/{id} maps a downstream failure to 502")
  void detailMapsDownstreamFailure() throws Exception {
    when(claimsClient.findClaim(CLAIM_ID))
        .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

    mockMvc
        .perform(get("/bff/v1/claims/{id}", CLAIM_ID))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("Downstream service error"));
  }

  @Test
  @DisplayName("GET /bff/v1/claims/{id} maps an unreachable downstream to 503")
  void detailMapsUnreachableDownstream() throws Exception {
    when(claimsClient.findClaim(CLAIM_ID))
        .thenThrow(new ResourceAccessException("Connection refused"));

    mockMvc
        .perform(get("/bff/v1/claims/{id}", CLAIM_ID))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Downstream service unavailable"));
  }

  @Test
  @DisplayName("CORS preflight from an allowed origin is accepted")
  void corsPreflightIsAllowed() throws Exception {
    mockMvc
        .perform(
            options("/bff/v1/claims")
                .header("Origin", "http://localhost:5199")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5199"));
  }

  @Test
  @DisplayName("CORS request from an unknown origin is rejected")
  void corsRejectsUnknownOrigin() throws Exception {
    mockMvc
        .perform(
            options("/bff/v1/claims")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("GET /bff/v1/claims passes the status filter downstream")
  void listPassesStatusFilter() throws Exception {
    when(claimsClient.findClaims(anyString(), eq(0), eq(20)))
        .thenReturn(new ClaimPagePayload(List.of(claimSummary()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/bff/v1/claims").param("status", "DRAFT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("DRAFT"));
  }

  private static ClaimSummaryResponse claimSummary() {
    return new ClaimSummaryResponse(
        CLAIM_ID,
        "CLM-20250310-ABCD1234",
        "POL-0001",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        "CAR_DAMAGE",
        "DRAFT",
        "Windscreen damaged",
        LocalDate.of(2025, 2, 1),
        OffsetDateTime.parse("2025-03-10T09:00:00Z"),
        OffsetDateTime.parse("2025-03-10T09:00:00Z"),
        List.of());
  }

  private static DocumentSummaryResponse documentSummary() {
    return new DocumentSummaryResponse(
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        CLAIM_ID,
        "policy.pdf",
        MediaType.APPLICATION_PDF_VALUE,
        3L,
        "INVOICE",
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        OffsetDateTime.parse("2025-03-10T09:00:00Z"));
  }
}
