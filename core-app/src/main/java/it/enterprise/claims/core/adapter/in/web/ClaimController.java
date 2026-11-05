package it.enterprise.claims.core.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.core.application.ClaimService;
import it.enterprise.claims.core.application.dto.ClaimCreateRequest;
import it.enterprise.claims.core.application.dto.ClaimDocumentLinkResponse;
import it.enterprise.claims.core.application.dto.ClaimPageResponse;
import it.enterprise.claims.core.application.dto.ClaimResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusHistoryResponse;
import it.enterprise.claims.core.application.dto.ClaimStatusUpdateRequest;
import it.enterprise.claims.core.application.dto.DocumentIdsRequest;
import it.enterprise.claims.core.domain.model.ClaimStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Claim endpoints, base path {@code /api/v1/claims} (blueprint §2.3).
 *
 * <p>Thin adapter: only HTTP concerns (status codes, {@code Location}, parameter normalisation).
 * The state machine and the validation live in {@link ClaimService}.
 */
@RestController
@RequestMapping(value = "/api/v1/claims", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Claims", description = "Claim lifecycle and status state machine")
public class ClaimController {

  private static final Set<String> SORTABLE_FIELDS =
      Set.of("openedAt", "updatedAt", "claimNumber", "status", "incidentDate");
  private static final Map<String, String> SORT_ALIASES = Map.of("createdAt", "openedAt");
  private static final String DEFAULT_SORT_PROPERTY = "openedAt";

  private final ClaimService claimService;

  /**
   * @param claimService claim use cases
   */
  public ClaimController(ClaimService claimService) {
    this.claimService = claimService;
  }

  /**
   * Lists claims, newest first by default.
   *
   * @param status optional status filter
   * @param page zero-based page index
   * @param size page size, clamped to 100
   * @param sort sort expression, e.g. {@code openedAt,desc} ({@code createdAt} is accepted as
   *     alias)
   * @return one page of claims
   */
  @GetMapping
  @Operation(summary = "List claims, optionally filtered by status")
  public ClaimPageResponse list(
      @RequestParam(required = false) ClaimStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "openedAt,desc") String sort) {
    return claimService.findAll(
        status,
        PageRequests.of(page, size, sort, SORTABLE_FIELDS, SORT_ALIASES, DEFAULT_SORT_PROPERTY));
  }

  /**
   * @param claimId identifier of the claim
   * @return the claim
   */
  @GetMapping("/{claimId}")
  @Operation(summary = "Get a claim by id")
  public ClaimResponse get(@PathVariable UUID claimId) {
    return claimService.findById(claimId);
  }

  /**
   * Opens a new claim in {@code DRAFT}.
   *
   * @param request payload
   * @return 201 with the created claim and its {@code Location}
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Open a claim on an existing policy")
  public ResponseEntity<ClaimResponse> create(@Valid @RequestBody ClaimCreateRequest request) {
    ClaimResponse created = claimService.open(request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{claimId}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  /**
   * @param claimId claim to update
   * @param request target status plus optional note
   * @return the updated claim
   */
  @PatchMapping(value = "/{claimId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Change the status of a claim (state machine enforced, 409 on illegal move)")
  public ClaimResponse changeStatus(
      @PathVariable UUID claimId, @Valid @RequestBody ClaimStatusUpdateRequest request) {
    return claimService.changeStatus(claimId, request);
  }

  /**
   * @param claimId claim whose audit trail is requested
   * @return the transitions in chronological order
   */
  @GetMapping("/{claimId}/status-history")
  @Operation(summary = "Chronological audit trail of the claim status transitions")
  public List<ClaimStatusHistoryResponse> statusHistory(@PathVariable UUID claimId) {
    return claimService.statusHistory(claimId);
  }

  /**
   * @param claimId claim to update
   * @param request document ids returned by document-service
   * @return the claim with the complete list of attached documents
   */
  @PostMapping(value = "/{claimId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Attach document-service documents to a claim (idempotent)")
  public ClaimResponse attachDocuments(
      @PathVariable UUID claimId, @Valid @RequestBody DocumentIdsRequest request) {
    return claimService.attachDocuments(claimId, request);
  }

  /**
   * @param claimId claim whose links are requested
   * @param includeUnlinked {@code true} to include the softly detached documents
   * @return the claim ⇄ document links, oldest first
   */
  @GetMapping("/{claimId}/documents")
  @Operation(summary = "List the document links of a claim (attached by default)")
  public List<ClaimDocumentLinkResponse> documents(
      @PathVariable UUID claimId,
      @RequestParam(defaultValue = "false") boolean includeUnlinked) {
    return claimService.findDocumentLinks(claimId, includeUnlinked);
  }

  /**
   * Softly detaches a document: the link row survives so the attachment history stays auditable.
   *
   * @param claimId claim to update
   * @param documentId document to detach
   * @return 204
   */
  @DeleteMapping("/{claimId}/documents/{documentId}")
  @Operation(summary = "Detach a document from a claim (soft, history preserved)")
  public ResponseEntity<Void> detachDocument(
      @PathVariable UUID claimId, @PathVariable UUID documentId) {
    claimService.unlinkDocument(claimId, documentId);
    return ResponseEntity.noContent().build();
  }
}
