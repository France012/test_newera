package it.enterprise.claims.core.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.enterprise.claims.core.application.PolicyService;
import it.enterprise.claims.core.application.dto.PolicyPageResponse;
import it.enterprise.claims.core.application.dto.PolicyResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Policy endpoints, base path {@code /api/v1/policies} (blueprint §2.3). */
@RestController
@RequestMapping(value = "/api/v1/policies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Policies", description = "Read access to the policies a claim can be opened on")
public class PolicyController {

  private static final Set<String> SORTABLE_FIELDS =
      Set.of("policyNumber", "validFrom", "validTo", "premium");
  private static final String DEFAULT_SORT_PROPERTY = "policyNumber";

  private final PolicyService policyService;

  /**
   * @param policyService policy use cases
   */
  public PolicyController(PolicyService policyService) {
    this.policyService = policyService;
  }

  /**
   * @param page zero-based page index
   * @param size page size, clamped to 100
   * @param sort sort expression, e.g. {@code policyNumber,asc}
   * @return one page of policies
   */
  @GetMapping
  @Operation(summary = "List policies")
  public PolicyPageResponse list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "policyNumber,asc") String sort) {
    return policyService.findAll(
        PageRequests.of(page, size, sort, SORTABLE_FIELDS, Map.of(), DEFAULT_SORT_PROPERTY));
  }

  /**
   * @param policyId identifier of the policy
   * @return the policy
   */
  @GetMapping("/{policyId}")
  @Operation(summary = "Get a policy by id")
  public PolicyResponse get(@PathVariable UUID policyId) {
    return policyService.findById(policyId);
  }
}
