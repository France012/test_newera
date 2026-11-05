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
| Executable SQL schema contract (`infra/db/verify/01-assert-schema.sql`, §8-bis) | **Data Architecture** | ✅ done                 |
| Repository **adapters** (entity ⇄ domain mapping)                      | Backend               | ✅ done (`Claim`/`Policy`/`Document`RepositoryAdapter) |
| Domain models, services, DTOs, controllers                             | Backend               | ✅ done                         |
| `application.yml` datasource/flyway properties                         | Backend               | ✅ done (§6 contract respected) |
| `docker-compose.yml` wiring of `infra/db/init`                         | Infra                 | ✅ done (`./db/init:/docker-entrypoint-initdb.d:ro`) |

> ⚠️ **Do not duplicate:** `it.enterprise.claims.core.domain.model.ClaimStatus`, all `*JpaEntity`
> classes and all `*JpaRepository` interfaces already exist and are owned by this layer.
> `ClaimStatus` is the **single source of truth** for the seven status values: the CHECK constraints
> in the migrations mirror it, and both guards below fail on any divergence.

> 📌 **Changing the schema (read before any `V2__`):** a change is complete only when **all four**
> artifacts move together in the same pull request — (1) the new Flyway migration, (2) the JPA
> entity, (3) `SchemaMigrationContractTest` (§7-bis), (4) `infra/db/verify/01-assert-schema.sql`
> (§8-bis). The Java test proves entity ⇄ SQL-text consistency; the SQL contract proves that
> PostgreSQL actually accepts and stores the result.

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

## 7-ter. Executable SQL schema contract (`infra/db/verify/01-assert-schema.sql`)

§7-bis compares the JPA entities with the **text** of the migration. It never opens a
connection, so it cannot prove that PostgreSQL accepts the migration or that the
objects really exist after `flyway migrate`. `01-assert-schema.sql` is that missing
half: a read-only script that runs **after** the migrations and aborts
(`raise exception` → exit code 1, `ON_ERROR_STOP`) on the first divergence from §4.

It creates nothing (only `pg_temp.*` helper functions), changes no data, and is
independent of the test classpath — so it can be attached to any stage that has a
real PostgreSQL.

| #   | Assertion group                                                                                                                 | Protects                                                    |
| --- | ------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| 1   | schemas `coreapp` + `documents` exist, no domain table leaks into `public`/the wrong schema                                      | DD-9, database-per-service                                  |
| 2   | all 6 tables exist                                                                                                              | lost/renamed migration                                      |
| 3   | every column: type, `varchar(n)` length, nullability, `numeric(12,2)` precision/scale                                            | silent truncation, `ddl-auto=validate` failure at startup   |
| 4   | every PK by name **and by column list** (incl. the composite `pk_claim_documents`)                                               | lost/surrogate PK, duplicate document links (DD-4)          |
| 5   | every unique key, every named CHECK, every FK by name                                                                           | dropped constraint                                          |
| 6   | `fk_claim_status_history_claim` / `fk_claim_documents_claim` are `ON DELETE CASCADE`                                             | orphaned audit/link rows                                    |
| 7   | **no** FK on `documents.claim_id` and on `claim_documents.document_id`                                                           | accidental cross-service FK (DD-5)                          |
| 8   | `ck_claims_status` literals == `ClaimStatus` exactly (set equality, sorted), and **no native PG enum type** exists               | §7.5 drift, `ALTER TYPE` locking risk (DD-7)                |
| 9   | 9 indexes exist, `idx_claims_opened_at` / `idx_documents_uploaded_at` keep `DESC`, `idx_documents_claim_active` keeps `WHERE deleted_at IS NULL` | lost/partial-index regressions (DD-6), lost list sort       |
| 10  | `checksum_sha256` is `varchar(64)` (not `char(64)`) + hex CHECK                                                                  | DD-2                                                        |
| 11  | if `flyway_schema_history` exists: no failed migration, `V1` recorded successful                                                 | half-applied schema                                         |

```bash
# after migrate (Flyway or the raw V1 scripts) on the same database
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
# => SCHEMA CONTRACT OK | 92 assertions | 6 tables · 2 schemas · 29 constraints · 9 indexes · 7 ClaimStatus values
```

`verify` is intentionally **not** wired into `.github/workflows/ci.yml` by this layer
(workflow ownership = DevOps/Infra). Ready-to-paste job, to be added once a PostgreSQL
service container is in the file:

```yaml
db-schema-contract:
  runs-on: ubuntu-latest
  services:
    postgres:
      image: postgres:16-alpine
      env:
        POSTGRES_USER: claims
        POSTGRES_PASSWORD: claims_ci_only
        POSTGRES_DB: claims
      ports: ['5442:5432']
      options: >-
        --health-cmd "pg_isready -U claims -d claims"
        --health-interval 5s --health-timeout 5s --health-retries 20
  steps:
    - uses: actions/checkout@v4
    - name: Apply migrations
      run: |
        for f in infra/db/init/01-init-schemas.sql \
                 core-app/src/main/resources/db/migration/*.sql \
                 document-service/src/main/resources/db/migration/*.sql; do
          psql "postgresql://claims:claims_ci_only@localhost:5442/claims" \
               -v ON_ERROR_STOP=1 -f "$f"
        done
    - name: Assert schema contract
      run: |
        psql "postgresql://claims:claims_ci_only@localhost:5442/claims" \
             -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
```

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

# 2) executable schema contract: objects, types, keys, indexes, enum values (§7-ter)
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
# => SCHEMA CONTRACT OK | 92 assertions | 6 tables · 2 schemas · 29 constraints · 9 indexes · 7 ClaimStatus values

# 3) JPA model compiles and matches the schema text
mvn -B -pl core-app,document-service -am verify   # includes spotless/checkstyle
```

> **Environment note (state of verification, no overclaiming)**: PostgreSQL, Docker and the
> Flyway CLI are **not installed** in the dev container, so nothing in this document has ever been
> executed against a live server from here. What *is* proven locally today:
>
> - migrations + init script **parse** with the real PostgreSQL grammar (`libpg_query` via
>   `pglast`): 19 + 10 + 4 statements, 0 parse errors; `01-assert-schema.sql` parses as 101
>   statements, 0 errors;
> - `SchemaMigrationContractTest` is **green in both modules** (6 tests each, 0 failures) —
>   entity ⇄ migration-text consistency;
> - entities and repositories **compile** under `javac --release 21 -Xlint:all -Werror`
>   and the modules pass Spotless + Checkstyle with 0 violations.
>
> Still unproven without a server: schema creation, `ddl-auto=validate` at startup, and
> `01-assert-schema.sql` actually passing. ⚠️ `.github/workflows/ci.yml` currently declares **no
> PostgreSQL service container**, so CI does not close this gap either — the `db-schema-contract`
> job in §7-ter is the proposed fix (workflow file is owned by DevOps/Infra).
