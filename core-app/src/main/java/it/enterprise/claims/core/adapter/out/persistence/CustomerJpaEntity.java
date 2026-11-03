package it.enterprise.claims.core.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of {@code coreapp.customers} (migration {@code V1__init_claims.sql}).
 *
 * <p>Entities of this package are persistence-only: they never leave the adapter layer, the mapping
 * to/from the domain model lives in the repository adapters.
 */
@Entity
@Table(
    name = "customers",
    schema = "coreapp",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_customers_fiscal_code", columnNames = "fiscal_code"))
public class CustomerJpaEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "fiscal_code", nullable = false, length = 16)
  private String fiscalCode;

  @Column(name = "full_name", nullable = false, length = 200)
  private String fullName;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  /** Required by JPA. */
  protected CustomerJpaEntity() {}

  public CustomerJpaEntity(UUID id, String fiscalCode, String fullName, String email) {
    this.id = id;
    this.fiscalCode = fiscalCode;
    this.fullName = fullName;
    this.email = email;
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

  public String getFiscalCode() {
    return fiscalCode;
  }

  public void setFiscalCode(String fiscalCode) {
    this.fiscalCode = fiscalCode;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
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
    if (!(other instanceof CustomerJpaEntity that)) {
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
    return "CustomerJpaEntity{id=" + id + ", fiscalCode='" + fiscalCode + "'}";
  }
}
