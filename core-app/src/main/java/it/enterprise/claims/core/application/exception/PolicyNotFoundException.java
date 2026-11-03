package it.enterprise.claims.core.application.exception;

/** Thrown when the policy referenced by a new claim does not exist - mapped to HTTP 404. */
public class PolicyNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String policyNumber;

  /**
   * @param policyNumber the business key that was not found
   */
  public PolicyNotFoundException(String policyNumber) {
    super("Policy " + policyNumber + " not found");
    this.policyNumber = policyNumber;
  }

  /**
   * @return the business key that was not found
   */
  public String getPolicyNumber() {
    return policyNumber;
  }
}
