# Database layer — TK-DE445B

Persistence design and code for the insurance-claims monorepo, as specified by
`docs/plan/TK-DE445B-blueprint.md` §2.6 (PostgreSQL 16, Flyway, database-per-service).

**Stack**: PostgreSQL 16 · Flyway (versioned, forward-only) · Spring Data JPA / Hibernate 6 ·
Maven modules `core-app` and `document-service` (Java 21, Spring Boot 3.3.5).

---

## 1. Ownership boundary (read this before writing code)

| Layer                                                                  | Owner                 | Status                          |
| ---------------------------------------------------------------------- | --------------------- | ------------------------------- |
| Flyway migrations (`db/migration/*.sql`)                               | **Data Architecture** | ✅ done, immutable once applied |
| JPA entities (`adapter/out/persistence/*JpaEntity.java`)               | **Data Architecture** | ✅ done                         |
| Spring Data repositories (`*JpaRepository.java`)                       | **Data Architecture** | ✅ done                         |
| `ClaimStatus` domain enum                                              | **Data Architecture** | ✅ done — **do not redefine**   |
| Schema ⇄ entity contract tests (`SchemaMigrationContractTest`, §7-bis) | **Data Architecture** | ✅ done                         |
| Repository **adapters** (entity ⇄ domain mapping)                      | Backend               | ⬜ to implement                 |
| Domain models, services, DTOs, controllers                             | Backend               | ⬜ to implement                 |
| `application.yml` datasource/flyway properties                         | Backend               | ⬜ to implement (see §6)        |
| `docker-compose.yml` wiring of `infra/db/init`                         | Infra                 | ⬜ to implement                 |

> ⚠️ **Already created, do not duplicate:** `it.enterprise.claims.core.domain.model.ClaimStatus`,
> all `*JpaEntity` classes and all `*JpaRepository` interfaces.
> The backend only has to write `ClaimRepositoryAdapter`, `PolicyRepositoryAdapter`,
> `DocumentRepositoryAdapter` (port → entity translation) and the services/controllers.

---

## 2. Schema overview

Two schemas inside one PostgreSQL 16 instance (dev) / one instance per service (prod).
No cross-service foreign keys: `claim_id` inside `documents` is a **logical** reference.

```mermaid
erDiagram
    customers ||--o{ policies : "customer_id"
    policies  ||--o{ claims   : "policy_id"
    claims    ||--o{ claim_status_history : "claim_id (cascade)"
    claims    ||--o{ claim_documents : "claim_id (cascade)"
    claims    ||..o{ documents : "claim_id (logical, other service)"

    customers {
        uuid id PK
        varchar fiscal_code UK
        varchar full_name
        varchar email
        timestamptz created_at
    }
    policies {
        uuid id PK
        varchar policy_number UK
        uuid customer_id FK
        varchar product_code
        date valid_from
        date valid_to
        numeric premium
        timestamptz created_at
    }
    claims {
        uuid id PK
        varchar claim_number UK
        uuid policy_id FK
        varchar claim_type
        varchar status "CHECK 7 values"
        varchar description
        date incident_date
        timestamptz opened_at
        timestamptz updated_at
        bigint version "optimistic lock"
    }
    claim_status_history {
        uuid id PK
        uuid claim_id FK
        varchar from_status "nullable"
        varchar to_status
        varchar note
        timestamptz changed_at
    }
    claim_documents {
        uuid claim_id PK,FK
        uuid document_id PK "logical ref"
        timestamptz linked_at
    }
    documents {
        uuid id PK
        uuid claim_id "logical ref"
        varchar file_name
        varchar content_type
        bigint size_bytes
        varchar document_type
        varchar checksum_sha256
        varchar storage_key UK
        integer version "optimistic lock"
        timestamptz uploaded_at
        timestamptz deleted_at "soft delete"
    }
```

---

## 3. Deliverables (files owned by this layer)

| Service                                 | Migration                                                | Entities                                                                                                                              | Repositories                                                                                                                          |
| --------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `core-app` (schema `coreapp`)           | `src/main/resources/db/migration/V1__init_claims.sql`    | `ClaimJpaEntity`, `PolicyJpaEntity`, `CustomerJpaEntity`, `ClaimStatusHistoryJpaEntity`, `ClaimDocumentJpaEntity` + `ClaimDocumentId` | `ClaimJpaRepository`, `PolicyJpaRepository`, `CustomerJpaRepository`, `ClaimStatusHistoryJpaRepository`, `ClaimDocumentJpaRepository` |
| `document-service` (schema `documents`) | `src/main/resources/db/migration/V1__init_documents.sql` | `DocumentJpaEntity`                                                                                                                   | `DocumentJpaRepository`                                                                                                               |
| domain                                  | —                                                        | `it.enterprise.claims.core.domain.model.ClaimStatus`                                                                                  | —                                                                                                                                     |
| local provisioning                      | `infra/db/init/01-init-schemas.sql` (not Flyway)         | —                                                                                                                                     | —                                                                                                                                     |

Java packages: `it.enterprise.claims.core…` / `it.enterprise.claims.documents…`
(blueprint §2.7). Entities are persistence-only and must never leak into the web/DTO layer.

---

## 4. Table reference & constraints

### 4.1 `coreapp.customers`

`pk_customers`, `uk_customers_fiscal_code`, CHECKs: fiscal code / full name not blank.
No index beyond the unique key (lookup by `fiscal_code` only).

### 4.2 `coreapp.policies`

`pk_policies`, `uk_policies_policy_number`, `fk_policies_customer → customers(id)`,
`ck_policies_validity_period (valid_to > valid_from)`, `ck_policies_premium_non_negative`,
`idx_policies_customer`. `policy_number` is what `POST /api/v1/claims` receives, so the unique
constraint is the lookup path.

### 4.3 `coreapp.claims`

`pk_claims`, `uk_claims_claim_number`, `fk_claims_policy → policies(id)`,
`ck_claims_status` (7 values, mirrors `ClaimStatus`), `ck_claims_updated_at_after_opened_at`,
`ck_claims_version_non_negative`.
Indexes and the query they serve:

| Index                         | Serves                                          |
| ----------------------------- | ----------------------------------------------- |
| `idx_claims_status`           | `GET /api/v1/claims?status=…`, dashboard counts |
| `idx_claims_policy`           | claims of one policy                            |
| `idx_claims_opened_at` (desc) | default list sort `sort=openedAt,desc`          |

`version` is the Hibernate `@Version` counter (optimistic locking); `updated_at` is maintained by
`@PreUpdate`, `opened_at` by `@PrePersist`.

### 4.4 `coreapp.claim_status_history`

Append-only audit trail: `pk_claim_status_history`, `fk_claim_status_history_claim → claims(id)
ON DELETE CASCADE`, CHECKs on both `from_status` (nullable, `NULL` for the opening transition) and
`to_status`, plus `ck_claim_status_history_actual_change (from_status <> to_status)`.
`idx_claim_status_history_claim (claim_id, changed_at desc)` serves
`GET /api/v1/claims/{id}/status-history`.

### 4.5 `coreapp.claim_documents`

Natural composite key `pk_claim_documents (claim_id, document_id)` — prevents duplicate
attachments; `fk_claim_documents_claim ON DELETE CASCADE`; `idx_claim_documents_document` for the
reverse lookup ("which claim owns this document id").

### 4.6 `documents.documents`

`pk_documents`, `uk_documents_storage_key`, `ck_documents_size_bytes_non_negative`,
`ck_documents_checksum_sha256_hex (~* '^[0-9a-f]{64}$')`, not-blank checks,
`ck_documents_deleted_at_after_uploaded_at`.
Indexes: `idx_documents_claim` (blueprint), `idx_documents_claim_active` (**partial**,
`where deleted_at is null` — the only path actually used to serve documents) and
`idx_documents_uploaded_at`.

---

## 5. Design decisions

| ID       | Decision                                                                                                                  | Rationale                                                                                                                                                                                                    |
| -------- | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **DD-1** | UUID PKs generated in the application layer, no DB default (`gen_random_uuid()` not used)                                 | blueprint §2.6; keeps ids known before flush, simplifies unit tests, no extension dependency                                                                                                                 |
| **DD-2** | `documents.checksum_sha256` is `varchar(64)`, **not** `char(64)` as drafted in §2.6                                       | PostgreSQL `char` is `bpchar`, which is not type-compatible with the Hibernate `String` mapping: `ddl-auto=validate` would fail at startup. Format is enforced by a hex CHECK, so the guarantee is identical |
| **DD-3** | Foreign keys mapped as scalar `UUID` fields, no `@ManyToOne` associations                                                 | persistence model stays free of lazy-loading/`LazyInitializationException` traps; the adapter decides when to fetch (e.g. policy → `policyNumber` for `ClaimResponse`)                                       |
| **DD-4** | `claim_documents` uses a composite key via `@EmbeddedId ClaimDocumentId`                                                  | the natural key is meaningful and prevents duplicate links; no surrogate id needed                                                                                                                           |
| **DD-5** | No cross-service FK (`documents.claim_id`, `claim_documents.document_id`)                                                 | database-per-service; referential integrity across services is enforced by the BFF/application, not by the DB                                                                                                |
| **DD-6** | Soft delete on documents (`deleted_at`) + partial index                                                                   | `DELETE /api/v1/documents/{id}` must stay auditable; every repository read method embeds `DeletedAtIsNull` so the filter cannot be forgotten                                                                 |
| **DD-7** | Status stored as `varchar(32)` + CHECK, mapped with `@Enumerated(EnumType.STRING)` — **no** native PostgreSQL `ENUM` type | native enums make value removal/reordering a locking `ALTER TYPE`; `varchar + CHECK` is a plain, transactional, Flyway-friendly change. The enum remains the single source of truth for the application      |
| **DD-8** | No DB triggers (`updated_at` handled by JPA callbacks)                                                                    | keeps the DB free of hidden behaviour and keeps entity state consistent with the row after flush; the column defaults are only safety nets for manual SQL                                                    |
| **DD-9** | All migration objects are schema-qualified (`coreapp.…`, `documents.…`)                                                   | the migration is correct regardless of the session `search_path` (CI, psql, Flyway)                                                                                                                          |

---

## 6. Flyway contract for `core-app` / `document-service`

The backend owns `application.yml`; it **must** contain the following (no hardcoded credentials —
values come from environment variables, see `.env.example`):

```yaml
spring:
  datasource:
    url: ${CORE_APP_DB_URL:jdbc:postgresql://localhost:5442/claims}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: coreapp # documents: documents
    default-schema: coreapp # documents: documents
    baseline-on-migrate: false
    validate-on-migrate: true
  jpa:
    hibernate:
      ddl-auto: validate # NEVER create/update: Flyway owns the schema
    properties:
      hibernate:
        default_schema: coreapp # documents: documents
    open-in-view: false
```

Key points:

- `ddl-auto: validate` — Hibernate only checks that entities match the migrated schema (this is why
  DD-2 exists). Never `update`/`create` in any profile.
- `spring.flyway.schemas` also creates the schema if missing; `infra/db/init/01-init-schemas.sql`
  is only a convenience for local docker-compose.
- **Tests must not need PostgreSQL** (blueprint constraint): unit tests + `@WebMvcTest` +
  Mockito only; if a data-layer test is ever needed, use `@DataJpaTest` with an explicit
  Testcontainers/embedded profile, never the default build.

---

## 7. Migration policy

1. **Forward-only and immutable.** A migration that reached any shared environment is never
   modified; new change = new file.
2. Naming: `V<n>__<snake_case_description>.sql` (e.g. `V2__add_claims_assigned_adjuster.sql`).
   No `R__` repeatables, no `U`/undo migrations (not supported by the community Flyway edition).
3. One schema per service; a service never touches the other service's schema.
4. Every new column is either nullable, has a `DEFAULT`, or is added in three steps
   (add nullable → backfill → set `NOT NULL`) to stay compatible with a rolling deploy.
5. Adding a `ClaimStatus` value requires **both** a new migration (drop + re-add
   `ck_claims_status`) **and** the enum change, in the same pull request.
6. Destructive changes (drop column/table) only with an explicit ADR and after the code that uses
   the object is removed.

---

## 7-bis. Automated guard (runs in `mvn verify`, no database)

The rules above are not documentation-only: each module has a
`SchemaMigrationContractTest` in `adapter/out/persistence` that reads the Flyway
migration **from the test classpath** and fails the build when the schema and the
JPA mapping drift apart. No PostgreSQL, no Testcontainers, no `@DataJpaTest` — so it
runs in the default build of every agent and in the CI `unit-test` job.

| Assertion                                                                                                                       | Protects                                                           |
| ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `@Table(schema)` / table name → `create table` present                                                                          | DD-9, table renames                                                |
| every `@Column` (incl. `@EmbeddedId` fields) exists in the table                                                                | entity without migration (the `ddl-auto=validate` startup failure) |
| `@Column(length)` ⇄ `varchar(n)`                                                                                                | silent truncation / validate mismatch                              |
| every `@UniqueConstraint` name exists                                                                                           | dropped unique key                                                 |
| `ClaimStatus` values ⇄ `ck_claims_status`, `ck_claim_status_history_*`                                                          | §7.5 (enum without migration, or vice versa)                       |
| `claims.version` is `bigint not null`                                                                                           | optimistic locking                                                 |
| `checksum_sha256` is `varchar(64)` + hex CHECK, `deleted_at` nullable + partial index                                           | DD-2, DD-6                                                         |
| `application.yml`: `flyway.enabled`, `schemas`, `baseline-on-migrate: false`, `ddl-auto: validate` and **no** `create`/`update` | §6 (Flyway owns the schema)                                        |

```bash
./mvnw -B -o -pl core-app,document-service test -Dtest='SchemaMigrationContractTest'
```

> Consequence for future work: a new column/value/constraint is added to the migration
> **and** the entity in the same commit, otherwise this test fails first.

---

## 8. How to verify locally

```bash
# 1) syntax/semantics of the migrations against a real PostgreSQL (host port 5442)
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f infra/db/init/01-init-schemas.sql
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f core-app/src/main/resources/db/migration/V1__init_claims.sql
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f document-service/src/main/resources/db/migration/V1__init_documents.sql
# or, with the Flyway CLI:
# flyway -url=jdbc:postgresql://localhost:5442/claims -schemas=coreapp \
#        -locations=filesystem:core-app/src/main/resources/db/migration migrate

# 2) JPA model compiles and matches the schema
mvn -B -pl core-app,document-service -am verify   # includes spotless/checkstyle
```

> **Environment note**: PostgreSQL, Docker and the Flyway CLI are **not installed** in the current
> dev container. The migrations were therefore validated with the real PostgreSQL grammar
> (`libpg_query`/`pglast`, 29 statements parsed, 0 errors) and every entity/repository was compiled
> with `javac --release 21 -Xlint:all -Werror`. Schema creation, `ddl-auto=validate` and the
> integration tests remain to be proven by CI (`.github/workflows/ci.yml`) where the PostgreSQL
> service container is available.
