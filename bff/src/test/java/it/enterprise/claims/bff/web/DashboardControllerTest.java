package it.enterprise.claims.bff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.enterprise.claims.bff.client.ClaimsClient;
import it.enterprise.claims.bff.config.CorsConfig;
import it.enterprise.claims.bff.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice of the dashboard controller: counter aggregation over the core-app client. */
@WebMvcTest(controllers = DashboardController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private ClaimsClient claimsClient;

  @Test
  @DisplayName("GET /bff/v1/dashboard/summary aggregates the counters per status")
  void summaryAggregatesCounters() throws Exception {
    when(claimsClient.countByStatus("DRAFT")).thenReturn(2L);
    when(claimsClient.countByStatus("SUBMITTED")).thenReturn(1L);
    when(claimsClient.countByStatus("UNDER_REVIEW")).thenReturn(3L);
    when(claimsClient.countByStatus("INTEGRATION_REQUESTED")).thenReturn(0L);
    when(claimsClient.countByStatus("APPROVED")).thenReturn(4L);
    when(claimsClient.countByStatus("REJECTED")).thenReturn(1L);
    when(claimsClient.countByStatus("CLOSED")).thenReturn(5L);

    mockMvc
        .perform(get("/bff/v1/dashboard/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalOpen").value(6))
        .andExpect(jsonPath("$.byStatus.DRAFT").value(2))
        .andExpect(jsonPath("$.byStatus.UNDER_REVIEW").value(3))
        .andExpect(jsonPath("$.byStatus.CLOSED").value(5));
  }
}
