package it.enterprise.claims.core.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.core.application.ClaimAssignmentService;
import it.enterprise.claims.core.application.dto.ClaimAssignmentRequest;
import it.enterprise.claims.core.application.dto.ClaimAssignmentResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints of the "attore + ruolo" assignments of a claim, base path {@code
 * /api/v1/claims/{claimId}/assignments} (blueprint §7.1).
 *
 * <p>{@code PUT} (and not {@code POST}) because the resource is identified by the role: there is at
 * most one active holder per {@code (claim, role)}, so the operation is an idempotent replacement.
 */
@RestController
@RequestMapping(
    value = "/api/v1/claims/{claimId}/assignments",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assignments", description = "Actors assigned to a claim, per role")
public class ClaimAssignmentController {

  private final ClaimAssignmentService claimAssignmentService;

  /**
   * @param claimAssignmentService assignment use cases
   */
  public ClaimAssignmentController(ClaimAssignmentService claimAssignmentService) {
    this.claimAssignmentService = claimAssignmentService;
  }

  /**
   * @param claimId claim whose assignments are requested
   * @param activeOnly {@code true} (default) to return only the current holders
   * @return the assignments, most recent first
   */
  @GetMapping
  @Operation(summary = "List the assignments of a claim")
  public List<ClaimAssignmentResponse> list(
      @PathVariable UUID claimId, @RequestParam(defaultValue = "true") boolean activeOnly) {
    return claimAssignmentService.findAll(claimId, activeOnly);
  }

  /**
   * Makes the given actor the current holder of a role, closing the previous one.
   *
   * @param claimId claim to assign
   * @param roleCode role to assign
   * @param request actor to assign
   * @return the new assignment
   */
  @PutMapping(value = "/{roleCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Assign an actor to a claim in a role (replaces the previous holder)")
  public ClaimAssignmentResponse assign(
      @PathVariable UUID claimId,
      @PathVariable String roleCode,
      @Valid @RequestBody ClaimAssignmentRequest request) {
    return claimAssignmentService.assign(claimId, roleCode, request);
  }

  /**
   * Closes the active assignment of a role; idempotent.
   *
   * @param claimId claim to update
   * @param roleCode role to close
   * @return 204
   */
  @DeleteMapping("/{roleCode}")
  @Operation(summary = "Un-assign the current holder of a role (soft, history preserved)")
  public ResponseEntity<Void> unassign(@PathVariable UUID claimId, @PathVariable String roleCode) {
    claimAssignmentService.unassign(claimId, roleCode);
    return ResponseEntity.noContent().build();
  }
}
