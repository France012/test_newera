package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.policies} (migration {@code V1__init_claims.sql}).
 *
 * <p>The {@code customer_id} foreign key is mapped as a raw {@link UUID} instead of a
 * {@code @ManyToOne} association (decision DD-1 in {@code docs/db/README.md}): it keeps the
 * persistence model free of lazy-loading surprises and lets the adapter decide when to fetch the
 * customer.
 */
@Entity
@Table(
    name = "policies",
    schema = "coreapp",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_policies_policy_number", columnNames = "policy_number"))
public class PolicyJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "policy_number", nullable = false, length = 32)
  private String policyNumber;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(name = "product_code", nullable = false, length = 32)
  private String productCode;

  @Column(name = "valid_from", nullable = false)
  private LocalDate validFrom;

  @Column(name = "valid_to", nullable = false)
  private LocalDate validTo;

  @Column(name = "premium", nullable = false, precision = 12, scale = 2)
  private BigDecimal premium;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  /** Required by JPA. */
  protected PolicyJpaEntity() {}

  public PolicyJpaEntity(
      UUID id,
      String policyNumber,
      UUID customerId,
      String productCode,
      LocalDate validFrom,
      LocalDate validTo,
      BigDecimal premium) {
    this.id = id;
    this.policyNumber = policyNumber;
    this.customerId = customerId;
    this.productCode = productCode;
    this.validFrom = validFrom;
    this.validTo = validTo;
    this.premium = premium;
  }

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getPolicyNumber() {
    return policyNumber;
  }

  public void setPolicyNumber(String policyNumber) {
    this.policyNumber = policyNumber;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public void setCustomerId(UUID customerId) {
    this.customerId = customerId;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public LocalDate getValidFrom() {
    return validFrom;
  }

  public void setValidFrom(LocalDate validFrom) {
    this.validFrom = validFrom;
  }

  public LocalDate getValidTo() {
    return validTo;
  }

  public void setValidTo(LocalDate validTo) {
    this.validTo = validTo;
  }

  public BigDecimal getPremium() {
    return premium;
  }

  public void setPremium(BigDecimal premium) {
    this.premium = premium;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PolicyJpaEntity that)) {
      return false;
    }
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public String toString() {
    return "PolicyJpaEntity{id=" + id + ", policyNumber='" + policyNumber + "'}";
  }
}
