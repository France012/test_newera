package it.enterprise.claims.core.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.core.application.ActorService;
import it.enterprise.claims.core.application.dto.ActorCreateRequest;
import it.enterprise.claims.core.application.dto.ActorPageResponse;
import it.enterprise.claims.core.application.dto.ActorResponse;
import it.enterprise.claims.core.application.dto.ActorRolesUpdateRequest;
import it.enterprise.claims.core.application.dto.ActorUpdateRequest;
import it.enterprise.claims.core.domain.model.ActorType;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Actor endpoints, base path {@code /api/v1/actors} (blueprint §7.1).
 *
 * <p>Thin adapter: only HTTP concerns (status codes, {@code Location}, parameter normalisation).
 * The duplicate-subject rule, the role vocabulary and the audit trail live in {@link ActorService}.
 */
@RestController
@RequestMapping(value = "/api/v1/actors", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Actors", description = "Actors (utenti) and their role grants")
public class ActorController {

  private static final Set<String> SORTABLE_FIELDS =
      Set.of("displayName", "subjectRef", "createdAt", "updatedAt");
  private static final Map<String, String> SORT_ALIASES = Map.of("name", "displayName");
  private static final String DEFAULT_SORT_PROPERTY = "displayName";

  private final ActorService actorService;

  /**
   * @param actorService actor use cases
   */
  public ActorController(ActorService actorService) {
    this.actorService = actorService;
  }

  /**
   * Lists actors, optionally filtered by role, type and enabled flag.
   *
   * @param role optional role code the actor must currently hold
   * @param actorType optional actor type
   * @param enabled optional enabled flag
   * @param page zero-based page index
   * @param size page size, clamped to 100
   * @param sort sort expression, e.g. {@code displayName,asc}
   * @return one page of live actors
   */
  @GetMapping
  @Operation(summary = "List actors, optionally filtered by role, type or enabled flag")
  public ActorPageResponse list(
      @RequestParam(required = false) String role,
      @RequestParam(required = false) ActorType actorType,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "displayName,asc") String sort) {
    return actorService.findAll(
        role,
        actorType,
        enabled,
        PageRequests.of(page, size, sort, SORTABLE_FIELDS, SORT_ALIASES, DEFAULT_SORT_PROPERTY));
  }

  /**
   * @param actorId identifier of the actor
   * @return the actor
   */
  @GetMapping("/{actorId}")
  @Operation(summary = "Get an actor by id")
  public ActorResponse get(@PathVariable UUID actorId) {
    return actorService.findById(actorId);
  }

  /**
   * Registers a new actor.
   *
   * @param request payload
   * @return 201 with the created actor and its {@code Location}
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Register an actor (409 when the subjectRef is already taken)")
  public ResponseEntity<ActorResponse> create(@Valid @RequestBody ActorCreateRequest request) {
    ActorResponse created = actorService.create(request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{actorId}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  /**
   * Updates the mutable attributes of an actor.
   *
   * @param actorId actor to update
   * @param request patch payload, {@code null} fields are left untouched
   * @return the updated actor
   */
  @PatchMapping(value = "/{actorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Update an actor's name, email or enabled flag")
  public ActorResponse update(
      @PathVariable UUID actorId, @Valid @RequestBody ActorUpdateRequest request) {
    return actorService.update(actorId, request);
  }

  /**
   * Replaces the role set of an actor (idempotent).
   *
   * @param actorId actor whose roles are set
   * @param request complete set of role codes
   * @return the actor with the effective role set
   */
  @PutMapping(value = "/{actorId}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Replace the role set of an actor (idempotent)")
  public ActorResponse replaceRoles(
      @PathVariable UUID actorId, @Valid @RequestBody ActorRolesUpdateRequest request) {
    return actorService.replaceRoles(actorId, request);
  }
}
