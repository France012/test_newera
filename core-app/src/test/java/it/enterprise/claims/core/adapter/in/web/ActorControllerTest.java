package it.enterprise.claims.core.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.enterprise.claims.core.application.ActorService;
import it.enterprise.claims.core.application.dto.ActorCreateRequest;
import it.enterprise.claims.core.application.dto.ActorPageResponse;
import it.enterprise.claims.core.application.dto.ActorResponse;
import it.enterprise.claims.core.application.dto.ActorRolesUpdateRequest;
import it.enterprise.claims.core.application.dto.ActorUpdateRequest;
import it.enterprise.claims.core.application.exception.ActorNotFoundException;
import it.enterprise.claims.core.application.exception.DuplicateActorException;
import it.enterprise.claims.core.application.exception.UnknownRoleException;
import it.enterprise.claims.core.domain.model.ActorType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice of the actor controller: payload shape, validation and error mapping. */
@WebMvcTest(controllers = ActorController.class)
class ActorControllerTest {

  private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String SUBJECT = "mario.rossi@example.com";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2025-03-10T09:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private ActorService actorService;

  @Test
  @DisplayName("GET /api/v1/actors returns the page envelope")
  void listReturnsPage() throws Exception {
    when(actorService.findAll(eq(null), eq(null), eq(null), any()))
        .thenReturn(new ActorPageResponse(List.of(actorResponse()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/api/v1/actors"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content[0].subjectRef").value(SUBJECT))
        .andExpect(jsonPath("$.content[0].roleCodes[0]").value("CLAIMS_OFFICER"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  @DisplayName("GET /api/v1/actors with an unknown role filter returns 400")
  void listRejectsUnknownActorType() throws Exception {
    mockMvc
        .perform(get("/api/v1/actors").param("actorType", "ALIEN"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  @DisplayName("POST /api/v1/actors returns 201 with a Location header")
  void createReturnsCreatedAndLocation() throws Exception {
    when(actorService.create(any(ActorCreateRequest.class))).thenReturn(actorResponse());

    String body =
        objectMapper.writeValueAsString(
            new ActorCreateRequest(SUBJECT, "Mario Rossi", SUBJECT, ActorType.INTERNAL, null));

    mockMvc
        .perform(post("/api/v1/actors").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/actors/" + ACTOR_ID))
        .andExpect(jsonPath("$.id").value(ACTOR_ID.toString()));
  }

  @Test
  @DisplayName("POST /api/v1/actors returns 409 when the subjectRef is already registered")
  void createReturnsConflictOnDuplicateSubject() throws Exception {
    when(actorService.create(any(ActorCreateRequest.class)))
        .thenThrow(new DuplicateActorException(SUBJECT));

    String body =
        objectMapper.writeValueAsString(
            new ActorCreateRequest(SUBJECT, "Mario Rossi", null, ActorType.INTERNAL, null));

    mockMvc
        .perform(post("/api/v1/actors").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Actor already registered"))
        .andExpect(jsonPath("$.subjectRef").value(SUBJECT));
  }

  @Test
  @DisplayName("POST /api/v1/actors returns 400 when a role code is unknown")
  void createReturnsBadRequestOnUnknownRole() throws Exception {
    when(actorService.create(any(ActorCreateRequest.class)))
        .thenThrow(new UnknownRoleException("NOPE"));

    String body =
        objectMapper.writeValueAsString(
            new ActorCreateRequest(
                SUBJECT, "Mario Rossi", null, ActorType.INTERNAL, Set.of("NOPE")));

    mockMvc
        .perform(post("/api/v1/actors").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Unknown role"))
        .andExpect(jsonPath("$.roleCode").value("NOPE"));
  }

  @Test
  @DisplayName("POST /api/v1/actors returns 400 with field errors when the body is invalid")
  void createReturnsValidationProblem() throws Exception {
    String body =
        objectMapper.writeValueAsString(
            new ActorCreateRequest("a b", "", "not-an-email", null, null));

    mockMvc
        .perform(post("/api/v1/actors").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.errors.subjectRef").exists())
        .andExpect(jsonPath("$.errors.displayName").exists())
        .andExpect(jsonPath("$.errors.email").exists())
        .andExpect(jsonPath("$.errors.actorType").exists());
  }

  @Test
  @DisplayName("PATCH /api/v1/actors/{id} returns 404 for an unknown actor")
  void updateReturnsNotFound() throws Exception {
    when(actorService.update(eq(ACTOR_ID), any(ActorUpdateRequest.class)))
        .thenThrow(new ActorNotFoundException(ACTOR_ID));

    mockMvc
        .perform(
            patch("/api/v1/actors/{id}", ACTOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Actor not found"));
  }

  @Test
  @DisplayName("PUT /api/v1/actors/{id}/roles returns the effective role set")
  void replaceRolesReturnsActor() throws Exception {
    when(actorService.replaceRoles(eq(ACTOR_ID), any(ActorRolesUpdateRequest.class)))
        .thenReturn(actorResponse());

    String body = objectMapper.writeValueAsString(new ActorRolesUpdateRequest(Set.of("APPRAISER")));

    mockMvc
        .perform(
            put("/api/v1/actors/{id}/roles", ACTOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roleCodes[0]").value("CLAIMS_OFFICER"));
  }

  private static ActorResponse actorResponse() {
    return new ActorResponse(
        ACTOR_ID,
        SUBJECT,
        "Mario Rossi",
        SUBJECT,
        ActorType.INTERNAL,
        true,
        List.of("CLAIMS_OFFICER"),
        NOW,
        NOW);
  }
}
