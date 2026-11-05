# Database layer — TK-DE445B

Persistence design and code for the insurance-claims monorepo, as specified by
`docs/plan/TK-DE445B-blueprint.md` §2.6 (PostgreSQL 16, Flyway, database-per-service).

**Stack**: PostgreSQL 16 · Flyway (versioned, forward-only) · Spring Data JPA / Hibernate 6 ·
Maven modules `core-app` and `document-service` (Java 21, Spring Boot 3.3.5).

**Since TK-64FBC8** the persistence layer also covers the Italian claims domain model of
pratiche assicurative: attori/utenti (`actors`), ruoli (`roles`, `actor_roles`), assegnazioni
(`claim_assignments`), a generic append-only audit trail (`audit_events`), the claim state
machine **enforced by the database** (`claim_status_transition` + composite FK from
`claim_status_history`) and the document lifecycle (versioning, `key_ref`, retention). All of it
lands in two additive `V2` migrations — the `V1` files stay immutable (§7.1).

---

## 1. Ownership boundary (read this before writing code)

| Layer                                                                           | Owner                 | Status                                                 |
| ------------------------------------------------------------------------------- | --------------------- | ------------------------------------------------------ |
| Flyway migrations (`db/migration/*.sql`)                                        | **Data Architecture** | ✅ done, immutable once applied                        |
| JPA entities (`adapter/out/persistence/*JpaEntity.java`)                        | **Data Architecture** | ✅ done                                                |
| Spring Data repositories (`*JpaRepository.java`)                                | **Data Architecture** | ✅ done                                                |
| `ClaimStatus` domain enum (10 states, state machine)                            | **Data Architecture** | ✅ done — **do not redefine**                          |
| `coreapp.claim_status_transition` seed (state machine in the database)          | **Data Architecture** | ✅ done (`V2`)                                         |
| Schema ⇄ entity contract tests (`SchemaMigrationContractTest`, §7-bis)          | **Data Architecture** | ✅ done                                                |
| Executable SQL schema contract (`infra/db/verify/01-assert-schema.sql`, §8-bis) | **Data Architecture** | ✅ done                                                |
| Repository **adapters** (entity ⇄ domain mapping)                               | Backend               | ✅ done (`Claim`/`Policy`/`Document`RepositoryAdapter) |
| Domain models, services, DTOs, controllers                                      | Backend               | ✅ done                                                |
| `application.yml` datasource/flyway properties                                  | Backend               | ✅ done (§6 contract respected)                        |
| `docker-compose.yml` wiring of `infra/db/init`                                  | Infra                 | ✅ done (`./db/init:/docker-entrypoint-initdb.d:ro`)   |

> ⚠️ **Do not duplicate:** `it.enterprise.claims.core.domain.model.ClaimStatus`, all `*JpaEntity`
> classes and all `*JpaRepository` interfaces already exist and are owned by this layer.
> `ClaimStatus` is the **single source of truth** for the ten status values: the CHECK constraints
> in the migrations and the seed of `coreapp.claim_status_transition` mirror it, and all three guards
> below (`SchemaMigrationContractTest`, `ClaimStatusTransitionContractTest`,
> `01-assert-schema.sql`) fail on any divergence.

> 📌 **Changing the schema (read before any `V3__`):** a change is complete only when **all four**
> artifacts move together in the same pull request — (1) the new Flyway migration, (2) the JPA
> entity, (3) `SchemaMigrationContractTest` (§7-bis), (4) `infra/db/verify/01-assert-schema.sql`
> (§8-bis). The Java test proves entity ⇄ SQL-text consistency; the SQL contract proves that
> PostgreSQL actually accepts and stores the result. A change of the state machine additionally
> requires `ClaimStatus` + the `claim_status_transition` seed + `ClaimStatusTransitionContractTest`
> (§7-bis).

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
    claims    ||--o{ claim_assignments : "claim_id (cascade)"
    claims    ||--o{ audit_events : "claim_id (cascade)"
    actors    ||--o{ actor_roles : "actor_id (cascade)"
    roles     ||--o{ actor_roles : "role_code"
    actors    ||--o{ claim_assignments : "actor_id"
    roles     ||--o{ claim_assignments : "role_code"
    actors    ||--o{ audit_events : "actor_id (set null)"
    claim_status_transition ||--o{ claim_status_history : "allowed (from,to)"
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
        varchar status "CHECK 10 values"
        varchar description
        date incident_date
        timestamptz opened_at
        timestamptz updated_at
        timestamptz closed_at "V2, nullable"
        timestamptz archived_at "V2, nullable"
        bigint version "optimistic lock"
    }
    claim_status_history {
        uuid id PK
        uuid claim_id FK
        varchar from_status "nullable"
        varchar to_status
        varchar note
        uuid actor_id "V2, nullable"
        timestamptz changed_at
    }
    claim_documents {
        uuid claim_id PK,FK
        uuid document_id PK "logical ref"
        timestamptz linked_at
        uuid linked_by_actor_id "V2, nullable"
        timestamptz unlinked_at "V2, soft un-link"
    }
    claim_status_transition {
        varchar from_status PK
        varchar to_status PK
    }
    roles {
        varchar code PK
        varchar name
        varchar description
        timestamptz created_at
    }
    actors {
        uuid id PK
        varchar subject_ref UK
        varchar display_name
        varchar email
        varchar actor_type
        boolean enabled
        timestamptz created_at
        timestamptz updated_at
        bigint version "optimistic lock"
        timestamptz deleted_at "soft delete"
    }
    actor_roles {
        uuid actor_id PK,FK
        varchar role_code PK,FK
        timestamptz granted_at
        uuid granted_by FK
        timestamptz revoked_at "soft revoke"
    }
    claim_assignments {
        uuid id PK
        uuid claim_id FK
        uuid actor_id FK
        varchar role_code FK
        timestamptz assigned_at
        uuid assigned_by FK
        timestamptz unassigned_at "soft un-assign"
    }
    audit_events {
        uuid id PK
        uuid claim_id "FK, nullable"
        uuid actor_id "FK, nullable"
        varchar event_type "regex format"
        varchar entity_type "regex format"
        uuid entity_id
        varchar outcome
        timestamptz occurred_at
        varchar request_id
        jsonb details
    }
    documents {
        uuid id PK
        uuid claim_id "logical ref"
        uuid document_key "V2, logical identity"
        integer version_number "V2, business version"
        boolean is_current "V2"
        uuid supersedes_id "V2, FK set null"
        varchar file_name
        varchar content_type
        bigint size_bytes
        varchar document_type
        varchar checksum_sha256
        varchar storage_key UK
        varchar key_ref "V2, KMS ref"
        date retention_until "V2"
        boolean legal_hold "V2"
        integer version "optimistic lock"
        timestamptz uploaded_at
        timestamptz deleted_at "soft delete"
    }
```

---

## 3. Deliverables (files owned by this layer)

| Service                                 | Migration                                            | Entities                                                                                                                              | Repositories                                                                                                                                 |
| --------------------------------------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `core-app` (schema `coreapp`)           | `db/migration/V1__init_claims.sql`                   | `ClaimJpaEntity`, `PolicyJpaEntity`, `CustomerJpaEntity`, `ClaimStatusHistoryJpaEntity`, `ClaimDocumentJpaEntity` + `ClaimDocumentId` | `ClaimJpaRepository`, `PolicyJpaRepository`, `CustomerJpaRepository`, `ClaimStatusHistoryJpaRepository`, `ClaimDocumentJpaRepository`        |
| `core-app` — TK-64FBC8                  | `db/migration/V2__claim_workflow_actors_audit.sql`   | `RoleJpaEntity`, `ActorJpaEntity`, `ActorRoleJpaEntity` + `ActorRoleId`, `ClaimAssignmentJpaEntity`, `AuditEventJpaEntity`            | `RoleJpaRepository`, `ActorJpaRepository`, `ActorRoleJpaRepository`, `ClaimAssignmentJpaRepository`, `AuditEventJpaRepository` (append-only) |
| `document-service` (schema `documents`) | `db/migration/V1__init_documents.sql`                | `DocumentJpaEntity`                                                                                                                   | `DocumentJpaRepository`                                                                                                                      |
| `document-service` — TK-64FBC8          | `db/migration/V2__document_versioning_retention.sql` | `DocumentJpaEntity` (versioning, `key_ref`, retention)                                                                                | `DocumentJpaRepository` (version lookups)                                                                                                    |
| domain                                  | —                                                    | `it.enterprise.claims.core.domain.model.ClaimStatus` (10 states + `allowedTransitions()`)                                             | —                                                                                                                                            |
| local provisioning                      | `infra/db/init/01-init-schemas.sql` (not Flyway)     | —                                                                                                                                     | —                                                                                                                                            |

Schema ⇄ model guards (all owned by this layer, all runnable without PostgreSQL):

| File                                                         | Proves                                                             |
| ------------------------------------------------------------ | ------------------------------------------------------------------ |
| `core-app/…/persistence/SchemaMigrationContractTest`         | 10 entities ⇄ `V1` + `V2` text                                     |
| `core-app/…/persistence/ClaimStatusTransitionContractTest`   | `ClaimStatus.allowedTransitions()` ⇄ the `V2` seed (14 pairs)      |
| `core-app/…/persistence/PersistenceEntityLifecycleTest`      | lifecycle callbacks, soft delete/un-assign, append-only repository |
| `document-service/…/persistence/SchemaMigrationContractTest` | `DocumentJpaEntity` ⇄ `V1` + `V2`, partial unique index, retention |
| `document-service/…/persistence/DocumentEntityLifecycleTest` | version chain, retention policy, soft delete                       |
| `infra/db/verify/01-assert-schema.sql`                       | the live PostgreSQL really accepted everything (§7-ter)            |

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

TK-64FBC8 (`V2`) adds the two reporting timestamps and widens the status CHECK:

| Column        | Type          | Constraint                                                                              |
| ------------- | ------------- | --------------------------------------------------------------------------------------- |
| `closed_at`   | `timestamptz` | nullable; `ck_claims_closed_at_requires_closed_status` (`status in (CLOSED, ARCHIVED)`) |
| `archived_at` | `timestamptz` | nullable; `ck_claims_archived_at_requires_archived_status` (`status = ARCHIVED`)        |

plus `ck_claims_closed_at_after_opened_at` (`closed_at >= opened_at`) and the partial index
`idx_claims_closed_at (closed_at desc) where closed_at is not null` (pratiche chiuse nel periodo).
`ck_claims_status` is dropped and re-added with **10** values (`APPRAISAL`, `SETTLEMENT`,
`ARCHIVED` added) — the widenings of a CHECK are line 5 of §7.

### 4.4 `coreapp.claim_status_history`

Append-only audit trail: `pk_claim_status_history`, `fk_claim_status_history_claim → claims(id)
ON DELETE CASCADE`, CHECKs on both `from_status` (nullable, `NULL` for the opening transition) and
`to_status`, plus `ck_claim_status_history_actual_change (from_status <> to_status)`.
`idx_claim_status_history_claim (claim_id, changed_at desc)` serves
`GET /api/v1/claims/{id}/status-history`.

`V2` adds `actor_id` (`uuid`, nullable, `fk_claim_status_history_actor on delete set null` — the
trail outlives the actor) and the composite foreign key
`fk_claim_status_history_transition (from_status, to_status) → claim_status_transition`. The FK is
`MATCH SIMPLE` on purpose: the opening row of a claim has `from_status = NULL`, so PostgreSQL skips
the check for it — that is the intended behaviour, not an oversight. Result: an illegal transition is
rejected by the database even if the application check is bypassed (§5 DD-10).

### 4.5 `coreapp.claim_documents`

Natural composite key `pk_claim_documents (claim_id, document_id)` — prevents duplicate
attachments; `fk_claim_documents_claim ON DELETE CASCADE`; `idx_claim_documents_document` for the
reverse lookup ("which claim owns this document id").

`V2` adds the soft un-link: `linked_by_actor_id` (`uuid`, nullable, `on delete set null`),
`unlinked_at` (`timestamptz`, nullable, `ck_claim_documents_unlinked_after_linked`) and the partial
index `idx_claim_documents_active (claim_id, linked_at desc) where unlinked_at is null`, which serves
`GET /api/v1/claims/{claimId}/documents` (default `includeUnlinked=false`).

### 4.6 `documents.documents`

`pk_documents`, `uk_documents_storage_key`, `ck_documents_size_bytes_non_negative`,
`ck_documents_checksum_sha256_hex (~* '^[0-9a-f]{64}$')`, not-blank checks,
`ck_documents_deleted_at_after_uploaded_at`.
Indexes: `idx_documents_claim` (blueprint), `idx_documents_claim_active` (**partial**,
`where deleted_at is null` — the only path actually used to serve documents) and
`idx_documents_uploaded_at`.

TK-64FBC8 (`V2`) adds the document lifecycle:

| Column            | Type           | Constraint / semantics                                                                                                           |
| ----------------- | -------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `document_key`    | `uuid`         | not null, **logical identity shared by all versions** (backfilled `= id` for existing rows)                                      |
| `version_number`  | `integer`      | not null default 1, `ck_documents_version_number_positive`, `uk_documents_document_key_version (document_key, version_number)`   |
| `is_current`      | `boolean`      | not null default true, one live current version per key (`uk_documents_current_version`, partial unique)                         |
| `supersedes_id`   | `uuid`         | nullable, `fk_documents_supersedes on delete set null`, `uk_documents_supersedes` (no forks), `ck_documents_supersedes_not_self` |
| `key_ref`         | `varchar(160)` | nullable, `ck_documents_key_ref_format` — opaque KMS alias/version, **not a secret**                                             |
| `retention_until` | `date`         | nullable, `ck_documents_retention_after_upload`                                                                                  |
| `legal_hold`      | `boolean`      | not null default false — blocks purge and soft delete regardless of `retention_until`                                            |

Indexes: `uk_documents_current_version` (**partial unique**, `(document_key) where is_current and
deleted_at is null`), `idx_documents_key_versions (document_key, version_number desc)` (version
history endpoint) and `idx_documents_retention_due (retention_until) where retention_until is not
null and legal_hold = false and deleted_at is null` (purge-job selector).

### 4.7 `coreapp.roles`

Reference data seeded by `V2` (the only allowed seed, §7): `CLAIMS_OFFICER` (Istruttore),
`APPRAISER` (Perito), `SETTLEMENT_OFFICER` (Liquidatore), `AUDITOR` (Revisore), `ADMIN`
(Amministratore). PK = `code` (`varchar(32)`, `ck_roles_code_format '^[A-Z][A-Z0-9_]{2,31}$'`), so a
role is identified by its business code everywhere (API, `actor_roles`, `claim_assignments`) and no
join on a surrogate id is ever needed. `ck_roles_name_not_blank`.

### 4.8 `coreapp.actors`

Attori / utenti. `pk_actors`, `uk_actors_subject_ref` (`subject_ref` = external IdP subject; it stays
unique **including soft-deleted rows**, because an identity is reactivated and never re-created),
`ck_actors_subject_ref_format`, `ck_actors_display_name_not_blank`, `ck_actors_email_format`,
`ck_actors_actor_type in (INTERNAL, EXTERNAL, SYSTEM)`, `ck_actors_updated_at_after_created_at`,
`ck_actors_version_non_negative`, `ck_actors_deleted_at_after_created_at`.
Soft delete (`deleted_at`) + `version bigint` (`@Version`).
Indexes: `idx_actors_type_enabled (actor_type) where deleted_at is null and enabled = true` and
`idx_actors_display_name (lower(display_name))`. Every repository read embeds `DeletedAtIsNull`
(DD-6).

### 4.9 `coreapp.actor_roles` and `coreapp.claim_assignments`

`actor_roles`: PK `(actor_id, role_code)`; `fk_actor_roles_actor on delete cascade`,
`fk_actor_roles_role → roles(code)`, `fk_actor_roles_granted_by on delete set null`,
`ck_actor_roles_revoked_after_granted`. Revoke = `revoked_at`, re-grant = `revoked_at = null` (no
delete); `idx_actor_roles_role (role_code) where revoked_at is null`.

`claim_assignments`: `pk_claim_assignments`; `fk_claim_assignments_claim on delete cascade`, FKs to
`actors` and `roles`, `fk_claim_assignments_assigned_by on delete set null`,
`ck_claim_assignments_unassigned_after_assigned`. The invariant **at most one active holder per
(claim, role)** is enforced by the partial unique index
`uk_claim_assignments_active_role (claim_id, role_code) where unassigned_at is null` (DD-15) — not by
the service, which would race under concurrency. Indexes:
`idx_claim_assignments_actor_active (actor_id, assigned_at desc) where unassigned_at is null`
("le mie pratiche") and `idx_claim_assignments_claim (claim_id, assigned_at desc)`.

### 4.10 `coreapp.audit_events`

Append-only audit trail (DD-11). `pk_audit_events`; `claim_id`/`actor_id` nullable
(`fk_audit_events_claim on delete cascade`, `fk_audit_events_actor on delete set null` — non-claim
events such as `ACTOR_CREATED` exist and must survive the actor); `event_type varchar(64)` and
`entity_type varchar(32)` validated by **regex format**
(`ck_audit_events_event_type_format '^[A-Z][A-Z0-9_]{2,63}$'`), never by an enumerated CHECK (DD-17);
`outcome in (SUCCESS, FAILURE)`; `occurred_at timestamptz`; `request_id varchar(64)` (correlation id,
caller-supplied in phase 1); `details jsonb` (free structured payload).
`(entity_type, entity_id)` is polymorphic by design → **no FK**, index only.
Indexes: `idx_audit_events_claim (claim_id, occurred_at desc)`,
`idx_audit_events_entity (entity_type, entity_id, occurred_at desc)`,
`idx_audit_events_actor (actor_id, occurred_at desc)`, `idx_audit_events_occurred_at (occurred_at
desc)`. Immutability is an ORM/repository guarantee (`@Immutable`, `updatable = false`, no
update/delete in the port), never a trigger (DD-8/DD-11).

### 4.11 `coreapp.claim_status_transition`

The state machine as data: PK `(from_status, to_status)`, `ck_claim_status_transition_from/to`
(10 values, mirror of `ClaimStatus`), `ck_claim_status_transition_actual_change`
(`from_status <> to_status`), seeded with the **14** pairs of §3 (`on conflict do nothing`, so the
migration is re-runnable). `claim_status_history` references it with the composite FK, which is what
turns the rule set into a database constraint (DD-10). A future state must be added to the enum, to
the two CHECKs, to the seed **and** to `ClaimStatusTransitionContractTest` in the same pull request.

---

## 5. Design decisions

| ID        | Decision                                                                                                                                                      | Rationale                                                                                                                                                                                                                                                                                                                |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **DD-1**  | UUID PKs generated in the application layer, no DB default (`gen_random_uuid()` not used)                                                                     | blueprint §2.6; keeps ids known before flush, simplifies unit tests, no extension dependency                                                                                                                                                                                                                             |
| **DD-2**  | `documents.checksum_sha256` is `varchar(64)`, **not** `char(64)` as drafted in §2.6                                                                           | PostgreSQL `char` is `bpchar`, which is not type-compatible with the Hibernate `String` mapping: `ddl-auto=validate` would fail at startup. Format is enforced by a hex CHECK, so the guarantee is identical                                                                                                             |
| **DD-3**  | Foreign keys mapped as scalar `UUID` fields, no `@ManyToOne` associations                                                                                     | persistence model stays free of lazy-loading/`LazyInitializationException` traps; the adapter decides when to fetch (e.g. policy → `policyNumber` for `ClaimResponse`)                                                                                                                                                   |
| **DD-4**  | `claim_documents` uses a composite key via `@EmbeddedId ClaimDocumentId`                                                                                      | the natural key is meaningful and prevents duplicate links; no surrogate id needed                                                                                                                                                                                                                                       |
| **DD-5**  | No cross-service FK (`documents.claim_id`, `claim_documents.document_id`)                                                                                     | database-per-service; referential integrity across services is enforced by the BFF/application, not by the DB                                                                                                                                                                                                            |
| **DD-6**  | Soft delete on documents (`deleted_at`) + partial index                                                                                                       | `DELETE /api/v1/documents/{id}` must stay auditable; every repository read method embeds `DeletedAtIsNull` so the filter cannot be forgotten                                                                                                                                                                             |
| **DD-7**  | Status stored as `varchar(32)` + CHECK, mapped with `@Enumerated(EnumType.STRING)` — **no** native PostgreSQL `ENUM` type                                     | native enums make value removal/reordering a locking `ALTER TYPE`; `varchar + CHECK` is a plain, transactional, Flyway-friendly change. The enum remains the single source of truth for the application                                                                                                                  |
| **DD-8**  | No DB triggers (`updated_at` handled by JPA callbacks)                                                                                                        | keeps the DB free of hidden behaviour and keeps entity state consistent with the row after flush; the column defaults are only safety nets for manual SQL                                                                                                                                                                |
| **DD-9**  | All migration objects are schema-qualified (`coreapp.…`, `documents.…`)                                                                                       | the migration is correct regardless of the session `search_path` (CI, psql, Flyway)                                                                                                                                                                                                                                      |
| **DD-10** | The state machine lives in the database too: `claim_status_transition` + composite FK `fk_claim_status_history_transition`                                    | AC-2 requires the transition constraints "a livello applicativo/**DB**". The enum pre-check exists only to answer `409` instead of surfacing a SQL error; the FK is the guarantee. `MATCH SIMPLE` lets the opening row (`from_status = NULL`) through, which is exactly what we need                                     |
| **DD-11** | `audit_events` is append-only through the ORM (`@Immutable`, every column `updatable = false`, repository port with `save` + finders only) and **no trigger** | A trigger would be hidden behaviour and would break the "no DB logic" rule of DD-8; compile-time absence of `delete`/`update` is stronger than a runtime check. `details` is `jsonb` (`@JdbcTypeCode(SqlTypes.JSON)`); fallback to `varchar` documented in plan risk R1                                                  |
| **DD-12** | Document versioning = `document_key` + `version_number` + `is_current`, one live current version per key (partial unique index)                               | A linear chain without forks that never needs `MAX(version_number)+1` at read time or `SELECT … FOR UPDATE`; the concurrent-version race is decided by PostgreSQL (`409` at the adapter), not by the service. The V1 `version` column stays the Hibernate `@Version` counter and is never reused for business versioning |
| **DD-13** | `storage_key` = physical object locator (unique, never exposed by the API); `key_ref` = opaque encryption-key reference (KMS alias/version, exposed)          | The ticket lists `storage_key` **and** `key_ref`: they answer two different questions (where is the object / which key encrypts it). Conflating them would leak the storage layout or lose the crypto provenance. KMS integration itself is out of scope                                                                 |
| **DD-14** | Retention = `retention_until date` + `legal_hold boolean`, with a partial index as the purge selector                                                         | Declarative and queryable; `legal_hold` overrides the date, so a legal dispute can freeze a document that would otherwise be purgeable. The purge job is out of scope — only the metadata contract is modelled                                                                                                           |
| **DD-15** | Assignments are soft-closed (`unassigned_at`) and "one active holder per (claim, role)" is a **partial unique index**                                         | The alternative (service-side check) loses under concurrency. Keeping the closed rows also preserves the assignment history without a second table                                                                                                                                                                       |
| **DD-16** | Audit lives **only** in `coreapp.audit_events`; `document-service` emits structured logs, no audit table                                                      | One aggregate → one owner → one Flyway history. Duplicating the trail in two schemas would make the two halves disagree after a partial failure and would need cross-service consistency we do not have                                                                                                                  |
| **DD-17** | `event_type`/`entity_type` are validated by **regex format**, not by an enumerated CHECK                                                                      | A new business event (e.g. `CLAIM_REOPENED`) must not require a migration; the format still prevents free text, typos and lower-case drift. Enumerating them would couple the write path of the audit trail to the release cycle                                                                                         |
| **DD-18** | `actorId` is **caller-asserted** (request body / query), not an authenticated principal                                                                       | ADR-0005: OIDC and authorization are out of scope in phase 1 (decision A9). Documented explicitly everywhere so nobody mistakes the field for a security decision: it records _who claims to have acted_, it does not authorise anything                                                                                 |

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
5. Adding a `ClaimStatus` value requires **all** of the following in the same pull request:
   the enum, a new migration (drop + re-add `ck_claims_status`,
   `ck_claim_status_history_{from,to}_status` **and** `ck_claim_status_transition_{from,to}`), the
   new seed rows of `coreapp.claim_status_transition`, `ClaimStatusTransitionContractTest`, the
   OpenAPI enums and this document. The transition set is the state machine: adding a state without
   its transitions produces an unreachable state, removing a transition produces claims that can
   never be closed.
6. Destructive changes (drop column/table) only with an explicit ADR and after the code that uses
   the object is removed.
7. From `V2` on, every statement is **guarded** so the migration is idempotent and applicable from
   zero together with `V1` (AC-1): `create table if not exists`, `add column if not exists`,
   `drop constraint if exists` + `add constraint`, `create index if not exists`,
   `insert … on conflict … do nothing`, and `alter column … set not null` **after** the backfill.
   A pre-existing row set that would violate a new rule is reported by an explicit
   `raise exception` guard with a `hint` — never silently relaxed (see
   `V2__claim_workflow_actors_audit.sql`, the `APPROVED → CLOSED` case of decision A10).

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

Additional rows introduced by TK-64FBC8:

| Assertion                                                                                                                       | Protects                                                  |
| ------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| every `V<n>` migration is read and `alter table … add column if not exists` is merged with `create table`                       | §7.4/§7.7 rolling deploy (a modified column is a new one) |
| the **last** definition of a dropped+re-added CHECK wins (`ck_claims_status`: 7 → 10 values)                                    | §7.5 (an enum value added without the migration)          |
| `coreapp.claim_status_transition` seed ⇄ `ClaimStatus.allowedTransitions()` (set equality)                                      | DD-10 (two sources of truth that must not diverge)        |
| `actors.deleted_at` nullable + partial index; `at most one active assignment` partial unique index                              | DD-6, DD-15                                               |
| `AuditEventJpaEntity` is `@Immutable`, every column `updatable = false`, `details` is `@JdbcTypeCode(JSON)` ⇄ `jsonb`           | DD-11, plan risk R1 (jsonb mapping)                       |
| `document_key`/`version_number`/`is_current` columns, partial unique `uk_documents_current_version`, `key_ref`, retention index | DD-12, DD-13, DD-14                                       |

```bash
./mvnw -B -o -pl core-app,document-service test \
  -Dtest='SchemaMigrationContractTest+ClaimStatusTransitionContractTest'
```

`ClaimStatusTransitionContractTest` (core-app) is the state-machine half: it reads the
`insert … values` seed from `V2__claim_workflow_actors_audit.sql` and asserts set equality with
`ClaimStatus.allowedTransitions()`, no self-transition, `ARCHIVED` as the only terminal state, full
reachability from `DRAFT`, the 14-pair count, and that `APPROVED → CLOSED` stays removed (A10).

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

| #   | Assertion group                                                                                                                                                                                                                                                                                            | Protects                                                  |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 1   | schemas `coreapp` + `documents` exist, no domain table leaks into `public`/the wrong schema                                                                                                                                                                                                                | DD-9, database-per-service                                |
| 2   | all 12 tables exist (6 original + 6 TK-64FBC8)                                                                                                                                                                                                                                                             | lost/renamed migration                                    |
| 3   | every column: type, `varchar(n)` length, nullability, `numeric(12,2)` precision/scale                                                                                                                                                                                                                      | silent truncation, `ddl-auto=validate` failure at startup |
| 4   | every PK by name **and by column list** (incl. the composite `pk_claim_documents`)                                                                                                                                                                                                                         | lost/surrogate PK, duplicate document links (DD-4)        |
| 5   | every unique key, every named CHECK, every FK by name                                                                                                                                                                                                                                                      | dropped constraint                                        |
| 6   | `fk_claim_status_history_claim` / `fk_claim_documents_claim` are `ON DELETE CASCADE`                                                                                                                                                                                                                       | orphaned audit/link rows                                  |
| 7   | **no** FK on `documents.claim_id` and on `claim_documents.document_id`                                                                                                                                                                                                                                     | accidental cross-service FK (DD-5)                        |
| 8   | `ck_claims_status` literals == `ClaimStatus` exactly (set equality, sorted), and **no native PG enum type** exists                                                                                                                                                                                         | §7.5 drift, `ALTER TYPE` locking risk (DD-7)              |
| 9   | the 24 indexes exist, `idx_claims_opened_at` / `idx_documents_uploaded_at` keep `DESC`, `idx_documents_claim_active` keeps `WHERE deleted_at IS NULL`                                                                                                                                                      | lost/partial-index regressions (DD-6), lost list sort     |
| 10  | `checksum_sha256` is `varchar(64)` (not `char(64)`) + hex CHECK                                                                                                                                                                                                                                            | DD-2                                                      |
| 11  | if `flyway_schema_history` exists: no failed migration, `V1` **and** `V2` recorded successful                                                                                                                                                                                                              | half-applied schema                                       |
| 12  | the 6 TK-64FBC8 tables exist; no domain table leaks into `public`/the wrong schema                                                                                                                                                                                                                         | DD-9, lost/renamed migration                              |
| 13  | `claims.closed_at`/`archived_at` + their 3 CHECKs + partial `idx_claims_closed_at`; `claim_status_history.actor_id` + composite FK `fk_claim_status_history_transition`                                                                                                                                    | DD-10, A10                                                |
| 14  | `claim_documents` soft un-link (`linked_by_actor_id`, `unlinked_at`, partial `idx_claim_documents_active`)                                                                                                                                                                                                 | soft un-link of a relation                                |
| 15  | 4 new `coreapp` tables asserted column-by-column (types, lengths, nullability, PK/UK/FK by name, `ON DELETE`, partial indexes); `details` is `jsonb`; `event_type` has a **format** CHECK and no enumerated CHECK; the 14 transition pairs are pinned by set equality with the seed                        | AC-2a, AC-2b, DD-11, DD-15, DD-17                         |
| 16  | `documents` versioning: 7 new columns with their types, `uk_documents_document_key_version`, linear chain (`uk_documents_supersedes`, `ck_documents_supersedes_not_self`), partial unique `uk_documents_current_version`, `idx_documents_key_versions`, `idx_documents_retention_due`, no FK on `claim_id` | DD-5, DD-12, DD-13, DD-14                                 |

```bash
# after migrate (Flyway, or the raw V1 + V2 scripts of both modules) on the same database
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
# => SCHEMA CONTRACT OK | 200 assertions | 12 tables · 2 schemas · 77 constraints · 24 indexes
#    · 10 ClaimStatus values · 14 transition rules
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
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f core-app/src/main/resources/db/migration/V2__claim_workflow_actors_audit.sql
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f document-service/src/main/resources/db/migration/V2__document_versioning_retention.sql
# from-zero + idempotency proof (AC-1): apply the whole chain TWICE on a fresh database —
# the second run must be a no-op (`CREATE … IF NOT EXISTS` notices only) and the assert
# script must still pass:
#   init → V1 core-app → V1 document-service → V2 core-app → V2 document-service (×2)
# or, with the Flyway CLI:
# flyway -url=jdbc:postgresql://localhost:5442/claims -schemas=coreapp \
#        -locations=filesystem:core-app/src/main/resources/db/migration migrate

# 2) executable schema contract: objects, types, keys, indexes, enum values (§7-ter)
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/claims" \
     -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
# => SCHEMA CONTRACT OK | 200 assertions | 12 tables · 2 schemas · 77 constraints · 24 indexes
#    · 10 ClaimStatus values · 14 transition rules

# 3) JPA model compiles and matches the schema text
mvn -B -pl core-app,document-service -am verify   # includes spotless/checkstyle
```

> **Environment note (state of verification, no overclaiming)**: PostgreSQL, Docker and the
> Flyway CLI are **not installed** in the dev container, so nothing in this document has ever been
> executed against a live server from here. What _is_ proven locally today (after TK-64FBC8):
>
> - all four migrations + the init script **parse** with the real PostgreSQL grammar
>   (`libpg_query` via `pglast`): 19 (V1 core-app) + 60 (V2 core-app) + 10 (V1 document-service) +
>   34 (V2 document-service) + 4 (init) statements, 0 parse errors; `01-assert-schema.sql` parses as
>   210 statements, 0 errors;
> - `SchemaMigrationContractTest` is **green in both modules** (9 + 11 tests) — entity ⇄
>   migration-text consistency over `V1` **and** `V2`;
> - `ClaimStatusTransitionContractTest` (7 tests) and the two entity-lifecycle suites are green —
>   state machine ⇄ seed parity, version chain, soft delete/un-assign, append-only audit;
> - entities and repositories **compile** under `javac --release 21 -Xlint:all` and the modules pass
>   Spotless + Checkstyle with 0 violations.
>
> Still unproven without a server: schema creation, the idempotent re-run of `V2` (AC-1),
> `ddl-auto=validate` at startup (the `jsonb` mapping of `audit_events.details` is the only
> pointer-typed column), and `01-assert-schema.sql` actually passing. ⚠️ `.github/workflows/ci.yml`
> currently declares **no PostgreSQL service container**, so CI does not close this gap either — the
> `db-schema-contract` job in §7-ter is the proposed fix (workflow file is owned by DevOps/Infra).
