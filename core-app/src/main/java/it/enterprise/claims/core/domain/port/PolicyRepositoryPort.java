package it.enterprise.claims.core.domain.port;

import it.enterprise.claims.core.domain.model.Policy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Outbound port for policy persistence (read-mostly: policies are provisioned elsewhere). */
public interface PolicyRepositoryPort {

  /**
   * @param policyNumber business key received by {@code POST /api/v1/claims}
   * @return the policy, or empty when no policy has that number
   */
  Optional<Policy> findByPolicyNumber(String policyNumber);

  /**
   * @param policyId identifier to look up
   * @return the policy, or empty when it does not exist
   */
  Optional<Policy> findById(UUID policyId);

  /**
   * @param pageable page request
   * @return the matching page of policies
   */
  Page<Policy> findPage(Pageable pageable);
}
