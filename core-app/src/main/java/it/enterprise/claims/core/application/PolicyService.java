package it.enterprise.claims.core.application;

import it.enterprise.claims.core.application.dto.PolicyPageResponse;
import it.enterprise.claims.core.application.dto.PolicyResponse;
import it.enterprise.claims.core.application.exception.PolicyNotFoundException;
import it.enterprise.claims.core.domain.port.PolicyRepositoryPort;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read use cases over policies. Policies are provisioned outside this service in phase 1. */
@Service
public class PolicyService {

  private final PolicyRepositoryPort policyRepository;

  /**
   * @param policyRepository policy persistence port
   */
  public PolicyService(PolicyRepositoryPort policyRepository) {
    this.policyRepository = policyRepository;
  }

  /**
   * @param pageable page request
   * @return the matching page of policies
   */
  @Transactional(readOnly = true)
  public PolicyPageResponse findAll(Pageable pageable) {
    return PolicyPageResponse.from(policyRepository.findPage(pageable));
  }

  /**
   * @param policyId identifier to read
   * @return the policy
   * @throws PolicyNotFoundException when no policy has that id
   */
  @Transactional(readOnly = true)
  public PolicyResponse findById(UUID policyId) {
    return policyRepository
        .findById(policyId)
        .map(PolicyResponse::from)
        .orElseThrow(() -> new PolicyNotFoundException(policyId.toString()));
  }
}
