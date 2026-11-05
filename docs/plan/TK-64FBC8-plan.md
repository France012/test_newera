# TK-64FBC8 — Domain model & DB schema for Italian insurance claims (pratiche assicurative)

> **Role of this document**: blueprint produced by the Software Architect. Developers implement
> exactly the files listed in §6, in the wave order of §9. Every path is **relative to the
> WORKSPACE ROOT** (`/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`).
>
> **Build on, do not reinvent**: TK-DE445B already delivered a working phase‑1 slice (claims,
> policies, documents, 7-state machine, Flyway V1, OpenAPI snapshots). TK-64FBC8 is an **additive
> evolution** of that baseline: it completes the domain model (actors, roles, assignments, audit
> trail), the workflow (perizia / liquidazione / archiviazione), the document lifecycle
> (versioning, `key_ref`, retention) and moves the state machine from _application-only_ enforcement
> to **application + database** enforcement.

---

## 0. Decisions locked by this blueprint (do not re-open)

| ID      | Decision                                                                                                                                                                            | Why (short)                                                                                                                                                         |
| ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A1**  | **V1 migrations are immutable.** Every change lands in a new `V2__*.sql` in each module.                                                                                            | `docs/db/README.md` §7.1 (forward-only). Phase‑1 CI/DB may already have applied V1.                                                                                 |
| **A2**  | **One canonical state machine**: `ClaimStatus` is _extended additively_ with `APPRAISAL`, `SETTLEMENT`, `ARCHIVED`. No renaming, **no second `workflow_state` column**.             | The ticket lists "stato / stato workflow" twice: a second status column would be duplicated truth. Renaming would break OpenAPI 0.1.0, `bff`, `frontend`, V1 CHECK. |
| **A3**  | **DB-level transition enforcement** via `coreapp.claim_status_transition` + composite FK from `claim_status_history`.                                                               | AC‑2 ("vincoli di transizione applicati a livello applicativo/**DB**"). The app still pre-checks to return `409` instead of a SQL error.                            |
| **A4**  | `actors`, `roles`, `actor_roles`, `claim_assignments`, `audit_events` live in **schema `coreapp`** (core‑app owns the claim aggregate).                                             | One aggregate → one owner → one Flyway history. Database-per-service is preserved (no cross-service FK).                                                            |
| **A5**  | `audit_events` is **append-only** and its `event_type` / `entity_type` are validated by **regex format**, never by an enumerated CHECK.                                             | A new business event must not require a migration. Append-only is enforced in the ORM (`@Immutable`, `updatable = false`) + repository port with no update/delete.  |
| **A6**  | Document versioning is **`document_key` (logical identity) + `version_number` + `is_current`**, with _one_ live current version per key (partial unique index).                     | Linear version chain, no forks, no `MAX()` scans; the existing `version` column stays the Hibernate `@Version` counter (never reuse it for business versioning).    |
| **A7**  | `documents.key_ref` = **opaque reference to the encryption key** (KMS alias/version), `storage_key` = physical object locator. `retention_until` (date) + `legal_hold` = retention. | Ticket field list (`hash, storage_key, key_ref, versione, data retention`) mapped 1:1; physical purge job stays out of scope.                                       |
| **A8**  | **No Testcontainers, no H2, no Docker-dependent test.** Schema ⇄ entity parity stays a _pure Java text contract test_ + the executable `infra/db/verify/01-assert-schema.sql`.      | `docs/db/README.md` §6/§8: the default build must never need PostgreSQL and this dev container has none.                                                            |
| **A9**  | Caller identity in phase 1 is a **request-body `actorId`**, not an authenticated principal.                                                                                         | ADR‑0005 (security out of scope in phase 1). Must be documented as _asserted, not verified_ — never as an authorization decision.                                   |
| **A10** | **Breaking transition**: `APPROVED → CLOSED` is removed; the path becomes `APPROVED → SETTLEMENT → CLOSED`.                                                                         | "liquidazione" must not be skippable, otherwise the PO requirement is cosmetic. No data migration needed (§5.4 guard).                                              |

Italian ↔ persisted mapping (documented in `ClaimStatus` javadoc, OpenAPI and `docs/db/README.md`):

| Ticket (IT)               | `ClaimStatus`           | Note                                       |
| ------------------------- | ----------------------- | ------------------------------------------ |
| aperta                    | `DRAFT`                 | pratica aperta, in compilazione            |
| inviata / presa in carico | `SUBMITTED`             |                                            |
| **in istruttoria**        | `UNDER_REVIEW`          | stato operativo principale                 |
| (sospesa)                 | `INTEGRATION_REQUESTED` | istruttoria sospesa in attesa integrazione |
| **perizia**               | `APPRAISAL`             | **nuovo**                                  |
| approvata (delibera)      | `APPROVED`              |                                            |
| respinta                  | `REJECTED`              |                                            |
| **liquidazione**          | `SETTLEMENT`            | **nuovo**                                  |
| **chiusa**                | `CLOSED`                |                                            |
| **archiviata**            | `ARCHIVED`              | **nuovo**, terminale                       |

---

## 1. Discovery — current state report

### 1.1 Tech stack (verified)

| Layer          | Reality in the repo                                                                                                                                                                                                  |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Backend        | Java 21 · Spring Boot 3.3.5 · Maven multi-module (`core-app`, `document-service`, `bff`), parent `claims-platform` 0.1.0                                                                                             |
| Web/API        | `spring-boot-starter-web` + bean validation + `springdoc-openapi` 2.6.0 (`/v3/api-docs`, `/swagger-ui.html`), RFC 9457 `ProblemDetail`                                                                               |
| Persistence    | PostgreSQL 16 · Spring Data JPA / Hibernate 6 · **Flyway owns the schema**, `ddl-auto: validate` in every profile                                                                                                    |
| Docs/contracts | `docs/api/{core-app,document-service,bff}.openapi.yaml` (hand-written snapshots, `info.version: 0.1.0`, gate ADR‑0009)                                                                                               |
| Frontend       | React 18 + TS 5.5 + Vite 5 + React Router 6, Vitest + Testing Library, ESLint 9 + Prettier 3                                                                                                                         |
| CI gates       | `.github/workflows/ci.yml`: `./mvnw -B -ntp verify` (tests + Spotless + Checkstyle), `npm run lint/format:check/test:run/build`, Prettier on the **whole repo**, YAML parse gate, secret gate, `VERSION == root pom` |
| Architecture   | Hexagonal-lite per module: `domain/{model,port}` · `application` + `application/dto` · `adapter/{in/web,out/persistence}` · `config` (ADR‑0002)                                                                      |

### 1.2 Existing persistence (V1, immutable)

| Schema      | Table                  | Owner    | Notes                                                                                                         |
| ----------- | ---------------------- | -------- | ------------------------------------------------------------------------------------------------------------- |
| `coreapp`   | `customers`            | core-app | `fiscal_code` unique → **contraente** already modelled (reachable via policy)                                 |
| `coreapp`   | `policies`             | core-app | `policy_number` unique, `valid_from/valid_to`, `premium numeric(12,2)`                                        |
| `coreapp`   | `claims`               | core-app | `claim_number` unique, `status` varchar(32) + `ck_claims_status` (7 values), `version bigint`                 |
| `coreapp`   | `claim_status_history` | core-app | append-only, `from_status` nullable = opening row                                                             |
| `coreapp`   | `claim_documents`      | core-app | composite PK `(claim_id, document_id)`, **logical** ref to document-service                                   |
| `documents` | `documents`            | doc-svc  | `document_type`, `checksum_sha256 varchar(64)`, `storage_key` unique, `version int`, `deleted_at` soft delete |

Guards already in place (must stay green, must be extended — never weakened):
`core-app/…/adapter/out/persistence/SchemaMigrationContractTest.java`,
`document-service/…/adapter/out/persistence/SchemaMigrationContractTest.java`,
`infra/db/verify/01-assert-schema.sql` (92 assertions), `docs/db/README.md` (source of truth).

### 1.3 Existing API surface (snapshot 0.1.0)

- `core-app` `:8180` — `GET/POST /api/v1/claims`, `GET /api/v1/claims/{id}`, `PATCH /api/v1/claims/{id}/status`,
  `GET /api/v1/claims/{id}/status-history`, `POST /api/v1/claims/{id}/documents`, `GET /api/v1/policies[/{id}]`.
- `document-service` `:8181` — upload / list-by-claim / metadata / content / soft delete.
- `bff` `:8185` — read-only aggregation + dashboard counters (hard-coded status lists).
- `frontend` — dashboard, claims list, claim detail; **degrades gracefully** on unknown statuses
  (`isKnownStatus`), so an added enum value cannot crash the SPA (verified in
  `frontend/src/types/claims.ts` + `frontend/src/pages/ClaimDetailPage.tsx:226`).

### 1.4 Gaps this task closes

| #   | Gap                                                                                                           | Closed by |
| --- | ------------------------------------------------------------------------------------------------------------- | --------- |
| G1  | No `attore`/`utente`, no `ruolo`, no assignment of an actor+role to a claim                                   | §5.1      |
| G2  | No generic `audit_event` (only the status-history trail)                                                      | §5.1      |
| G3  | State machine has no `perizia` / `liquidazione` / `archiviazione`; transitions are **not** enforced in the DB | §5.2      |
| G4  | No transition rules table → `claim_status_history` can store any pair                                         | §5.2      |
| G5  | Documents have no version chain, no `key_ref`, no retention metadata                                          | §5.3      |
| G6  | `claim_documents` has no actor/timestamp of the un-link (soft delete of a relation)                           | §5.1      |
| G7  | Contracts (OpenAPI) not updated/versioned for the new capabilities                                            | §7        |
| G8  | No repository-level tests for the new adapters                                                                | §8        |

---

## 2. Requirements — acceptance criteria (verifiable)

| AC        | Statement (from the ticket)                                  | Objective check added by this blueprint                                                                                                                                                                                                                                            |
| --------- | ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **AC‑1**  | Migrazioni idempotenti e applicabili da zero                 | `V2` files use only guarded DDL (`if not exists`, `drop constraint if exists` + `add constraint`, `insert … on conflict do nothing`); re-running them on a migrated DB is a no-op. Both V2 files must be applied **from an empty database** together with V1 without manual steps. |
| **AC‑2a** | Vincoli di integrità a livello applicativo/DB                | All new tables: PK, named CHECK, named FK, named unique constraints, `on delete` policy declared in §5. Entity ⇄ migration parity proved by the extended contract test.                                                                                                            |
| **AC‑2b** | Vincoli di **transizione di stato** a livello applicativo/DB | `ClaimStatus.canTransitionTo` (409 before the write) **and** `coreapp.claim_status_transition` + `fk_claim_status_history_transition` (a history row with a non-allowed pair is rejected by PostgreSQL). Parity test §8.2.                                                         |
| **AC‑3**  | Contratto OpenAPI pubblicato e versionato                    | `docs/api/core-app.openapi.yaml`, `docs/api/document-service.openapi.yaml`, `docs/api/bff.openapi.yaml` → `info.version: 0.2.0`, new paths/schemas, enum updated. `VERSION` + root/3 module `pom.xml` = `0.2.0`.                                                                   |
| **AC‑4**  | Test di repository/schema verdi                              | `./mvnw -B -ntp verify` green on all modules with the new `*RepositoryAdapterTest`, `*ServiceTest`, `*ControllerTest`, `SchemaMigrationContractTest`, `ClaimStatusTransitionContractTest` (§8).                                                                                    |
| **AC‑5**  | (derived) Nessuna regressione                                | Existing suites (`ClaimServiceTest`, `ClaimControllerTest`, `DocumentServiceTest`, `DocumentControllerTest`, `bff` tests, `frontend` tests) still green after the 3 added statuses.                                                                                                |

### Definition of Done

1. The 2 migrations exist, are guarded/idempotent, and are the **only** schema change.
2. Every new table/column is mapped by exactly one JPA entity, and `SchemaMigrationContractTest` proves the parity.
3. `ClaimStatus` (10 values) == `ck_claims_status` == `ck_claim_status_history_{to,from}_status` == `ck_claim_status_transition_{from,to}` == seed rows of `claim_status_transition` == OpenAPI enums.
4. `infra/db/verify/01-assert-schema.sql` extended (new tables/columns/indexes/FKs/transition pairs) and its summary counts updated; `infra/db/verify/README.md` updated.
5. `docs/db/README.md` updated (ER diagram, §4 tables, DD‑10…DD‑18, §7‑bis table, §7‑ter groups).
6. OpenAPI snapshots parse (CI `repo-hygiene`) and are Prettier-clean.
7. `./mvnw -B -ntp verify` and `npm run lint && npm run format:check && npm run test:run && npm run build` (in `frontend/`) are green.
8. No secret, no hardcoded credential, no new hardcoded host:port (`server.port` unchanged: 8180/8181/8185).

---

## 3. Target workflow state machine (`ClaimStatus`, 10 values)

```
DRAFT ──► SUBMITTED ──► UNDER_REVIEW ──┬──► INTEGRATION_REQUESTED ──┐
                                       │                            │
                                       │◄───────────────────────────┘
                                       ├──► APPRAISAL ──┬──► UNDER_REVIEW   (back to istruttoria)
                                       │                ├──► APPROVED
                                       │                └──► REJECTED
                                       ├──► APPROVED ──► SETTLEMENT ──► CLOSED ──► ARCHIVED (terminal)
                                       └──► REJECTED ─────────────────► CLOSED
```

Authoritative transition set (14 pairs) — **this exact list** is the seed of `coreapp.claim_status_transition`
and the body of `ClaimStatus.allowedTransitions()`:

| from                    | to                                                           |
| ----------------------- | ------------------------------------------------------------ |
| `DRAFT`                 | `SUBMITTED`                                                  |
| `SUBMITTED`             | `UNDER_REVIEW`                                               |
| `UNDER_REVIEW`          | `INTEGRATION_REQUESTED`, `APPRAISAL`, `APPROVED`, `REJECTED` |
| `INTEGRATION_REQUESTED` | `UNDER_REVIEW`                                               |
| `APPRAISAL`             | `UNDER_REVIEW`, `APPROVED`, `REJECTED`                       |
| `APPROVED`              | `SETTLEMENT`                                                 |
| `SETTLEMENT`            | `CLOSED`                                                     |
| `REJECTED`              | `CLOSED`                                                     |
| `CLOSED`                | `ARCHIVED`                                                   |
| `ARCHIVED`              | — (terminal)                                                 |

Constraints: no self-transition, no transition from a state to itself, `ARCHIVED` is terminal,
`APPROVED → CLOSED` no longer allowed (**A10**).

### 3.1 Java spec for `ClaimStatus` (MODIFY, do not rewrite the file's javadoc structure)

`core-app/src/main/java/it/enterprise/claims/core/domain/model/ClaimStatus.java`

```java
public enum ClaimStatus {
  DRAFT, SUBMITTED, UNDER_REVIEW, INTEGRATION_REQUESTED,
  APPRAISAL, APPROVED, REJECTED, SETTLEMENT, CLOSED, ARCHIVED;

  public Set<ClaimStatus> allowedTransitions() {
    return switch (this) {
      case DRAFT -> EnumSet.of(SUBMITTED);
      case SUBMITTED -> EnumSet.of(UNDER_REVIEW);
      case UNDER_REVIEW -> EnumSet.of(INTEGRATION_REQUESTED, APPRAISAL, APPROVED, REJECTED);
      case INTEGRATION_REQUESTED -> EnumSet.of(UNDER_REVIEW);
      case APPRAISAL -> EnumSet.of(UNDER_REVIEW, APPROVED, REJECTED);
      case APPROVED -> EnumSet.of(SETTLEMENT);
      case SETTLEMENT -> EnumSet.of(CLOSED);
      case REJECTED -> EnumSet.of(CLOSED);
      case CLOSED -> EnumSet.of(ARCHIVED);
      case ARCHIVED -> EnumSet.noneOf(ClaimStatus.class);
    };
  }

  public boolean isTerminal() { return this == ARCHIVED; }
}
```

> Keep the enum **declaration order = lifecycle order** (it drives UI ordering, dashboard and docs).
> Write one `case` per state — no combined labels, no clever ternaries: it keeps the parity test
> readable and the diff of a future state addition obvious.

---

## 4. DB schema changes

### 4.1 CREATE `core-app/src/main/resources/db/migration/V2__claim_workflow_actors_audit.sql`

Header must follow the V1 style (module, owner, Flyway, immutability, schema qualification, DD-9).
Order matters — the FKs require the referenced tables to exist first.

**Step 1 — `coreapp.roles` (lookup, seeded)**

| Column        | Type                                   | Constraints                                                          |
| ------------- | -------------------------------------- | -------------------------------------------------------------------- |
| `code`        | `varchar(32)` not null                 | `pk_roles`; `ck_roles_code_format (code ~ '^[A-Z][A-Z0-9_]{2,31}$')` |
| `name`        | `varchar(100)` not null                | `ck_roles_name_not_blank`                                            |
| `description` | `varchar(500)`                         |                                                                      |
| `created_at`  | `timestamptz` not null default `now()` |                                                                      |

Seed (idempotent, `on conflict (code) do nothing`): `CLAIMS_OFFICER` (Istruttore), `APPRAISER`
(Perito), `SETTLEMENT_OFFICER` (Liquidatore), `AUDITOR` (Revisore), `ADMIN` (Amministratore).
Reference data is the only allowed seed (needed for FK integrity); no business/demo rows.

**Step 2 — `coreapp.actors` (attori / utenti)**

| Column                      | Type                                   | Constraints                                                                                            |
| --------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `id`                        | `uuid` not null                        | `pk_actors`                                                                                            |
| `subject_ref`               | `varchar(128)` not null                | `uk_actors_subject_ref`; `ck_actors_subject_ref_format (subject_ref ~ '^[A-Za-z0-9._:@                 | /-]{3,128}$')`; **external IdP subject** (A9) |
| `display_name`              | `varchar(200)` not null                | `ck_actors_display_name_not_blank`                                                                     |
| `email`                     | `varchar(255)`                         | `ck_actors_email_format (email is null or email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$')` |
| `actor_type`                | `varchar(32)` not null                 | `ck_actors_actor_type in ('INTERNAL','EXTERNAL','SYSTEM')`                                             |
| `enabled`                   | `boolean` not null default `true`      |                                                                                                        |
| `created_at` / `updated_at` | `timestamptz` not null default `now()` | `ck_actors_updated_at_after_created_at`                                                                |
| `version`                   | `bigint` not null default `0`          | `ck_actors_version_non_negative` (Hibernate `@Version`)                                                |
| `deleted_at`                | `timestamptz`                          | **soft delete** `ck_actors_deleted_at_after_created_at`                                                |

Indexes: `idx_actors_type_enabled (actor_type) where deleted_at is null and enabled = true`,
`idx_actors_display_name (lower(display_name))`.
`subject_ref` stays unique even after soft delete (an identity is reactivated, never re-created) — document it.

**Step 3 — `coreapp.actor_roles` (ruolo assegnato a un attore)**

| Column       | Type                                   | Constraints                                                 |
| ------------ | -------------------------------------- | ----------------------------------------------------------- |
| `actor_id`   | `uuid` not null                        | `fk_actor_roles_actor → actors(id) on delete cascade`       |
| `role_code`  | `varchar(32)` not null                 | `fk_actor_roles_role → roles(code)`                         |
| `granted_at` | `timestamptz` not null default `now()` |                                                             |
| `granted_by` | `uuid`                                 | `fk_actor_roles_granted_by → actors(id) on delete set null` |
| `revoked_at` | `timestamptz`                          | `ck_actor_roles_revoked_after_granted`                      |

PK `pk_actor_roles (actor_id, role_code)`; revoke = set `revoked_at`, re-grant = set it back to `null`
(documented; no audit of intermediate grants beyond `audit_events`).
Index: `idx_actor_roles_role (role_code) where revoked_at is null`.

**Step 4 — `coreapp.claim_assignments` (attore + ruolo su una pratica)**

| Column          | Type                                   | Constraints                                                        |
| --------------- | -------------------------------------- | ------------------------------------------------------------------ |
| `id`            | `uuid` not null                        | `pk_claim_assignments`                                             |
| `claim_id`      | `uuid` not null                        | `fk_claim_assignments_claim → claims(id) on delete cascade`        |
| `actor_id`      | `uuid` not null                        | `fk_claim_assignments_actor → actors(id)`                          |
| `role_code`     | `varchar(32)` not null                 | `fk_claim_assignments_role → roles(code)`                          |
| `assigned_at`   | `timestamptz` not null default `now()` |                                                                    |
| `assigned_by`   | `uuid`                                 | `fk_claim_assignments_assigned_by → actors(id) on delete set null` |
| `unassigned_at` | `timestamptz`                          | `ck_claim_assignments_unassigned_after_assigned` (soft un-assign)  |

Indexes:
`uk_claim_assignments_active_role` **unique partial** `(claim_id, role_code) where unassigned_at is null`
→ _at most one active holder per role per claim_ (DB-enforced);
`idx_claim_assignments_actor_active (actor_id, assigned_at desc) where unassigned_at is null` ("my claims");
`idx_claim_assignments_claim (claim_id, assigned_at desc)`.

**Step 5 — `coreapp.audit_events` (append-only audit trail, A5)**

| Column        | Type                                       | Constraints                                                                                                           |
| ------------- | ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| `id`          | `uuid` not null                            | `pk_audit_events`                                                                                                     |
| `claim_id`    | `uuid`                                     | `fk_audit_events_claim → claims(id) on delete cascade` (null for non-claim events, e.g. `ACTOR_CREATED`)              |
| `actor_id`    | `uuid`                                     | `fk_audit_events_actor → actors(id) on delete set null`                                                               |
| `event_type`  | `varchar(64)` not null                     | `ck_audit_events_event_type_format (event_type ~ '^[A-Z][A-Z0-9_]{2,63}$')`                                           |
| `entity_type` | `varchar(32)` not null                     | `ck_audit_events_entity_type_format (~ '^[A-Z][A-Z0-9_]{2,31}$')`                                                     |
| `entity_id`   | `uuid` not null                            |                                                                                                                       |
| `outcome`     | `varchar(16)` not null default `'SUCCESS'` | `ck_audit_events_outcome in ('SUCCESS','FAILURE')`                                                                    |
| `occurred_at` | `timestamptz` not null default `now()`     |                                                                                                                       |
| `request_id`  | `varchar(64)`                              | `ck_audit_events_request_id_format (null or ~ '^[A-Za-z0-9._:-]{1,64}$')` (correlation id; phase 1 = client-supplied) |
| `details`     | `jsonb`                                    | free structured payload (before/after, note, reason)                                                                  |

Indexes: `idx_audit_events_claim (claim_id, occurred_at desc)`,
`idx_audit_events_entity (entity_type, entity_id, occurred_at desc)`,
`idx_audit_events_actor (actor_id, occurred_at desc)`, `idx_audit_events_occurred_at (occurred_at desc)`.
No FK on `(entity_type, entity_id)` (polymorphic by design — document as DD).
Append-only: **no UPDATE/DELETE path** in the code (entity `@Immutable`, all `@Column(updatable = false)`,
repository port exposes `append` + finders only). No DB trigger (DD‑8).

**Step 6 — `coreapp.claim_status_transition` (state-machine rules, A3)**

| Column        | Type                   | Constraints                                   |
| ------------- | ---------------------- | --------------------------------------------- |
| `from_status` | `varchar(32)` not null | `ck_claim_status_transition_from` (10 values) |
| `to_status`   | `varchar(32)` not null | `ck_claim_status_transition_to` (10 values)   |

`pk_claim_status_transition (from_status, to_status)`;
`ck_claim_status_transition_actual_change (from_status <> to_status)`.
Seed = the 17 pairs of §3, `on conflict do nothing`.

**Step 7 — extend the existing tables**

```sql
-- claims: reporting dates + 10-value CHECK
alter table coreapp.claims add column if not exists closed_at   timestamptz;
alter table coreapp.claims add column if not exists archived_at timestamptz;
alter table coreapp.claims drop constraint if exists ck_claims_status;
alter table coreapp.claims add  constraint ck_claims_status check (status in ('DRAFT','SUBMITTED','UNDER_REVIEW','INTEGRATION_REQUESTED','APPRAISAL','APPROVED','REJECTED','SETTLEMENT','CLOSED','ARCHIVED'));
-- new CHECKs (named, so the assert script can pin them):
--   ck_claims_closed_at_requires_closed_status   (closed_at is null or status in ('CLOSED','ARCHIVED'))
--   ck_claims_archived_at_requires_archived_status (archived_at is null or status = 'ARCHIVED')
--   ck_claims_closed_at_after_opened_at          (closed_at is null or closed_at >= opened_at)
create index if not exists idx_claims_closed_at on coreapp.claims (closed_at desc) where closed_at is not null;

-- claim_status_history: who performed the transition + DB-enforced transition pair
alter table coreapp.claim_status_history add column if not exists actor_id uuid;
-- + fk_claim_status_history_actor, ck_* re-created with 10 values, guard, then:
alter table coreapp.claim_status_history drop constraint if exists fk_claim_status_history_transition;
alter table coreapp.claim_status_history
  add constraint fk_claim_status_history_transition
  foreign key (from_status, to_status) references coreapp.claim_status_transition (from_status, to_status);

-- claim_documents: soft un-link + actor
alter table coreapp.claim_documents add column if not exists linked_by_actor_id uuid;
alter table coreapp.claim_documents add column if not exists unlinked_at timestamptz;
-- + fk to actors (on delete set null), ck_claim_documents_unlinked_after_linked,
--   idx_claim_documents_active (claim_id, linked_at desc) where unlinked_at is null
```

**Mandatory pre-FK guard** (fail loud, never silently relax — `docs/db/README.md` §7.4):
if `claim_status_history` already contains a pair that the new rules do not allow
(e.g. legacy `APPROVED → CLOSED`), the migration must `raise exception` with the offending pairs
and an actionable `hint`, and the operator decides (backfill the rules table or the history).
On an empty/fresh database this branch is a no-op.

> `from_status is null` on the opening row: the composite FK is `MATCH SIMPLE`, so a `NULL`
> `from_status` **skips** the check. That is the intended behaviour (there is no previous state) —
> call it out in the migration comment, it is the non-obvious part of the design.

### 4.2 CREATE `document-service/src/main/resources/db/migration/V2__document_versioning_retention.sql`

All statements guarded (`add column if not exists`, `drop constraint if exists` + `add constraint`,
`create index if not exists`); backfill **before** `set not null` (rolling-deploy rule §7.4).

| Column            | Type                               | Constraints / semantics                                                                                                                                         |
| ----------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `document_key`    | `uuid` not null                    | **logical document identity** shared by all its versions (backfill: `= id`)                                                                                     |
| `version_number`  | `integer` not null default `1`     | `ck_documents_version_number_positive (>= 1)`; `uk_documents_document_key_version (document_key, version_number)`                                               |
| `is_current`      | `boolean` not null default `true`  | exactly one live current version per key (partial unique, below)                                                                                                |
| `key_ref`         | `varchar(160)`                     | **A7**: opaque encryption-key reference (KMS alias/version); `ck_documents_key_ref_format (null or ~ '^[A-Za-z0-9._:/-]{1,160}$')`                              |
| `retention_until` | `date`                             | end of the retention period; `ck_documents_retention_after_upload (null or >= timezone('UTC', uploaded_at)::date)`                                              |
| `legal_hold`      | `boolean` not null default `false` | blocks purge regardless of `retention_until`                                                                                                                    |
| `supersedes_id`   | `uuid`                             | `fk_documents_supersedes → documents(id) on delete set null`, `uk_documents_supersedes` unique, `ck_documents_supersedes_not_self` → **linear chain, no forks** |

Indexes: `uk_documents_current_version` (**partial unique** `(document_key) where is_current and deleted_at is null`),
`idx_documents_key_versions (document_key, version_number desc)`,
`idx_documents_retention_due (retention_until) where retention_until is not null and legal_hold = false and deleted_at is null`
(purge job selector), plus keep the V1 indexes untouched.
`storage_key` stays unique per physical object; the `checksum_sha256` unchanged (content hash per version).

**Idempotency/from-zero proof** for both files:
`infra/scripts/verify-db-from-zero.sh` is **out of scope** (Infra); the from-zero check is documented in
`docs/db/README.md` §8 as the `psql` pipeline (init → V1 → V2 ×2 modules → `01-assert-schema.sql`),
and the CI job snippet already proposed in §7‑ter is extended by one line per module.

---

## 5. Domain model

### 5.1 core-app — bounded context unchanged (`it.enterprise.claims.core`)

New aggregates/entities (all pure records, no JPA/JSON annotations — ADR‑0002):

| Type              | Package        | Shape (record components)                                                                                                                                  |
| ----------------- | -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Actor`           | `domain.model` | `id, subjectRef, displayName, email, type(ActorType), enabled, Set<String> roleCodes, createdAt, updatedAt`                                                |
| `ActorType`       | `domain.model` | `INTERNAL, EXTERNAL, SYSTEM`                                                                                                                               |
| `Role`            | `domain.model` | `code, name, description`                                                                                                                                  |
| `ClaimAssignment` | `domain.model` | `id, claimId, actorId, actorDisplayName, roleCode, assignedAt, assignedByActorId, unassignedAt`                                                            |
| `AuditEvent`      | `domain.model` | `id, claimId, actorId, eventType(AuditEventType), entityType, entityId, outcome, occurredAt, requestId, Map<String,Object> details`                        |
| `AuditEventType`  | `domain.model` | `CLAIM_OPENED, CLAIM_STATUS_CHANGED, CLAIM_DOCUMENT_LINKED, CLAIM_DOCUMENT_UNLINKED, CLAIM_ASSIGNED, CLAIM_UNASSIGNED, ACTOR_CREATED, ACTOR_ROLES_CHANGED` |

Ports: `ActorRepositoryPort`, `RoleRepositoryPort`, `ClaimAssignmentPort`, `AuditEventPort`
(the last exposes **only** `append(AuditEvent)` + finders — A5).

`Claim` record: **unchanged components**; add `closedAt`/`archivedAt` only inside the persistence
entity + `ClaimResponse` (§7) — keep the domain record additive and nullable to avoid touching every
constructor call. Invariant helpers to add: `Claim.isClosed()`, `Claim.isArchived()`.

`ClaimStatusHistory` record: `+ actorId`, `+ actorDisplayName` (resolved by the adapter, like
`policyNumber` today).

### 5.2 Application rules (services)

| Rule                     | Where                                                                                              | Behaviour                                                                                                                                                                                                                             |
| ------------------------ | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Illegal transition       | `ClaimService.changeStatus`                                                                        | pre-check with `ClaimStatus.canTransitionTo` → `409 InvalidClaimStatusTransitionException` (unchanged), **then** the DB FK is the second line of defence                                                                              |
| Status dates             | `ClaimService.changeStatus`                                                                        | entering `CLOSED` sets `closed_at`, entering `ARCHIVED` sets `archived_at`                                                                                                                                                            |
| Audit                    | `ClaimService` (open / changeStatus / attachDocuments) + `ActorService` + `ClaimAssignmentService` | one `append` per state change, in the **same transaction** (no async, no outbox in phase 1)                                                                                                                                           |
| Unique active assignment | `ClaimAssignmentService.assign`                                                                    | close the previous active row (`unassigned_at = now`) and insert the new one; the partial unique index is the guard                                                                                                                   |
| Actor/role validation    | `ActorService`                                                                                     | `subjectRef` duplicate → `409`; unknown `roleCode` → `400` (`UnknownRoleException`); unknown actor → `404`                                                                                                                            |
| Retention                | `DocumentService.updateRetention`                                                                  | `retention_until` in the past → `400`; `legal_hold` blocks soft delete (`DeleteDocument` → `409` while on hold)                                                                                                                       |
| Document version         | `DocumentService.uploadNewVersion`                                                                 | `version_number = max + 1` of the same `document_key`, previous row `is_current = false`, new row `is_current = true`, `supersedes_id = previous.id`; concurrent version creation → `409` (optimistic lock / unique violation mapped) |

### 5.3 Persistence mapping rules (must follow `docs/db/README.md` DD‑1…DD‑9)

- UUID PK generated in the application layer (`UUID.randomUUID()`) — no DB default.
- FKs mapped as **scalar `UUID` fields**, no `@ManyToOne` (DD‑3).
- `timestamptz` ⇄ `OffsetDateTime`; `date` ⇄ `LocalDate`; `jsonb` ⇄ `String` + `@JdbcTypeCode(SqlTypes.JSON)`.
- Soft delete: every read method of `ActorJpaRepository` embeds `DeletedAtIsNull` (DD‑6).
- `@Version bigint` on `actors`; `@Version` on `documents` stays as-is.
- All `@Table`/`@Column` schema-qualified with `schema = "coreapp"` / `"documents"` (DD‑9).

---

## 6. File inventory (EXACT paths inside WORKSPACE ROOT)

### 6.1 CREATE — migrations & DB contracts

| File                                                                                     | Content |
| ---------------------------------------------------------------------------------------- | ------- |
| `core-app/src/main/resources/db/migration/V2__claim_workflow_actors_audit.sql`           | §4.1    |
| `document-service/src/main/resources/db/migration/V2__document_versioning_retention.sql` | §4.2    |

### 6.2 MODIFY — DB contracts & docs

| File                                               | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `infra/db/verify/01-assert-schema.sql`             | new assertion groups: 4 new `coreapp` tables + 2 new `documents` columns groups; every new column type/length/nullability; PK/UK/FK by name; `on delete cascade/set null`; partial indexes (`uk_claim_assignments_active_role`, `uk_documents_current_version`, `idx_documents_retention_due`); `ck_claims_status` literals == 10 `ClaimStatus` values; `claim_status_transition` seed **set-equality** with the enum; `details` is `jsonb`; no cross-schema FK added. Update header comment + final summary counts (was `92 assertions | 6 tables | 9 indexes`). |
| `infra/db/verify/README.md`                        | counts + one paragraph on the transition-rules assertions                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `docs/db/README.md`                                | ER diagram (+4 coreapp tables, new document columns), §3 deliverables, §4 new tables `4.7…4.11`, §5 DD‑10…DD‑18 (see below), §7‑bis assertion table rows, §7‑ter groups, §8 commands (V1 **and** V2 per module)                                                                                                                                                                                                                                                                                                                         |
| `docs/db/README.md` DD list to add                 | DD‑10 state machine rules in DB; DD‑11 append-only audit (`@Immutable`, no trigger); DD‑12 `document_key`/`version_number`/`is_current` (why not `MAX()`); DD‑13 `key_ref` vs `storage_key`; DD‑14 retention + legal hold; DD‑15 assignments partial unique; DD‑16 audit lives only in `coreapp` (document-service emits structured logs); DD‑17 regex-validated `event_type`/`entity_type`; DD‑18 caller-asserted `actorId` until OIDC (A9)                                                                                            |
| `docs/architecture/README.md`                      | module table: "7-state machine" → "10-state machine + actors/roles/audit"; ADR index row for 0013                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `core-app/README.md`, `document-service/README.md` | state machine diagram (§3), document versioning/retention/scheduling semantics                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

### 6.3 CREATE — architecture decision record

`docs/architecture/adr/0013-claims-workflow-actors-audit-and-document-versioning.md`
Sections: Context · Decision (A2/A3/A5/A6/A7/A10) · Rejected alternatives (second `workflow_state`
column; native PG enum; triggers for immutability; `MAX(version)+1` chain without `is_current`;
document-service audit table) · Consequences (breaking `APPROVED → CLOSED`, OpenAPI 0.2.0, purge job
out of scope) · References. ADRs are immutable once accepted (ADR index rule).

### 6.4 CREATE/MODIFY — core-app Java (all under `core-app/src/main/java/it/enterprise/claims/core/`)

| Path                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Action                                                                                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| `domain/model/ClaimStatus.java`                                                                                                                                                                                                                                                                                                                                                                                                                                                      | **MODIFY** (A2: +3 values, new transitions, IT mapping javadoc)                                       |
| `domain/model/Claim.java`                                                                                                                                                                                                                                                                                                                                                                                                                                                            | MODIFY (add `closedAt`, `archivedAt`, `isClosed()`, `isArchived()`)                                   |
| `domain/model/ClaimStatusHistory.java`                                                                                                                                                                                                                                                                                                                                                                                                                                               | MODIFY (+`actorId`, +`actorDisplayName`)                                                              |
| `domain/model/Actor.java`, `ActorType.java`, `Role.java`, `ClaimAssignment.java`, `AuditEvent.java`, `AuditEventType.java`                                                                                                                                                                                                                                                                                                                                                           | **CREATE**                                                                                            |
| `domain/port/ActorRepositoryPort.java`, `RoleRepositoryPort.java`, `ClaimAssignmentPort.java`, `AuditEventPort.java`                                                                                                                                                                                                                                                                                                                                                                 | **CREATE**                                                                                            |
| `domain/port/ClaimRepositoryPort.java`                                                                                                                                                                                                                                                                                                                                                                                                                                               | MODIFY (`updateStatus(claim, target, note, actorId)`, `findDocumentLinks(claimId)`, `unlinkDocument`) |
| `application/ActorService.java`, `application/ClaimAssignmentService.java`, `application/AuditRecorder.java`                                                                                                                                                                                                                                                                                                                                                                         | **CREATE**                                                                                            |
| `application/ClaimService.java`                                                                                                                                                                                                                                                                                                                                                                                                                                                      | MODIFY (audit append on open/status/link; `actorId` propagation; closed/archived timestamps)          |
| `application/dto/ActorCreateRequest.java`, `ActorUpdateRequest.java`, `ActorRolesUpdateRequest.java`, `ActorResponse.java`, `ActorPageResponse.java`, `RoleResponse.java`, `ClaimAssignmentRequest.java`, `ClaimAssignmentResponse.java`, `AuditEventResponse.java`, `AuditEventPageResponse.java`, `ClaimDocumentLinkResponse.java`                                                                                                                                                 | **CREATE** (records + `jakarta.validation` annotations)                                               |
| `application/dto/ClaimStatusUpdateRequest.java`                                                                                                                                                                                                                                                                                                                                                                                                                                      | MODIFY (+`@Nullable UUID actorId`)                                                                    |
| `application/dto/ClaimStatusHistoryResponse.java`, `ClaimResponse.java`                                                                                                                                                                                                                                                                                                                                                                                                              | MODIFY (+actor fields; +`closedAt`/`archivedAt`)                                                      |
| `application/exception/ActorNotFoundException.java`, `DuplicateActorException.java`, `UnknownRoleException.java`, `InvalidAssignmentException.java`, `DocumentOnLegalHoldException.java`                                                                                                                                                                                                                                                                                             | **CREATE**                                                                                            |
| `adapter/in/web/ActorController.java`, `RoleController.java`, `ClaimAssignmentController.java`, `ClaimAuditEventController.java`                                                                                                                                                                                                                                                                                                                                                     | **CREATE**                                                                                            |
| `adapter/in/web/ClaimController.java`                                                                                                                                                                                                                                                                                                                                                                                                                                                | MODIFY (`+GET /{claimId}/documents`, actorId in status patch)                                         |
| `adapter/in/web/GlobalExceptionHandler.java`                                                                                                                                                                                                                                                                                                                                                                                                                                         | MODIFY (404 actor, 409 duplicate/assignment/legal-hold, 400 unknown role)                             |
| `adapter/out/persistence/ActorJpaEntity.java`, `ActorJpaRepository.java`, `ActorRepositoryAdapter.java`, `ActorRoleJpaEntity.java`, `ActorRoleId.java`, `ActorRoleJpaRepository.java`, `RoleJpaEntity.java`, `RoleJpaRepository.java`, `RoleRepositoryAdapter.java`, `ClaimAssignmentJpaEntity.java`, `ClaimAssignmentJpaRepository.java`, `ClaimAssignmentPersistenceAdapter.java`, `AuditEventJpaEntity.java`, `AuditEventJpaRepository.java`, `AuditEventPersistenceAdapter.java` | **CREATE**                                                                                            |
| `adapter/out/persistence/ClaimJpaEntity.java`                                                                                                                                                                                                                                                                                                                                                                                                                                        | MODIFY (+`closedAt`, `archivedAt`)                                                                    |
| `adapter/out/persistence/ClaimStatusHistoryJpaEntity.java`                                                                                                                                                                                                                                                                                                                                                                                                                           | MODIFY (+`actorId`)                                                                                   |
| `adapter/out/persistence/ClaimDocumentJpaEntity.java`                                                                                                                                                                                                                                                                                                                                                                                                                                | MODIFY (+`linkedByActorId`, `unlinkedAt`)                                                             |
| `adapter/out/persistence/ClaimRepositoryAdapter.java`, `ClaimJpaRepository.java`, `ClaimDocumentJpaRepository.java`                                                                                                                                                                                                                                                                                                                                                                  | MODIFY (actor-aware history insert, active-link queries)                                              |
| `config/OpenApiConfig.java`                                                                                                                                                                                                                                                                                                                                                                                                                                                          | MODIFY (tags: Actors, Roles, Assignments, Audit)                                                      |

### 6.5 CREATE/MODIFY — document-service Java (`document-service/src/main/java/it/enterprise/claims/documents/`)

| Path                                                                                                             | Action                                                                                                                                           |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `domain/model/Document.java`                                                                                     | MODIFY (+`documentKey`, `versionNumber`, `isCurrent`, `keyRef`, `retentionUntil`, `legalHold`)                                                   |
| `domain/model/DocumentVersion.java`                                                                              | **CREATE** (lightweight version summary for the history endpoint)                                                                                |
| `domain/port/DocumentRepositoryPort.java`                                                                        | MODIFY (`findVersions(documentKey)`, `findCurrentByClaim`, `saveVersion`, `updateRetention`)                                                     |
| `application/DocumentService.java`                                                                               | MODIFY (`uploadNewVersion`, `findVersions`, `updateRetention`, `currentOnly` filter, legal-hold guard on delete)                                 |
| `application/dto/DocumentResponse.java`                                                                          | MODIFY (+new fields; **never** expose `storageKey`)                                                                                              |
| `application/dto/DocumentVersionResponse.java`, `DocumentRetentionUpdateRequest.java`                            | **CREATE**                                                                                                                                       |
| `application/exception/RetentionViolationException.java`, `DocumentVersionConflictException.java`                | **CREATE**                                                                                                                                       |
| `adapter/in/web/DocumentController.java`                                                                         | MODIFY (+`GET /{documentId}/versions`, `POST /{documentId}/versions`, `PATCH /{documentId}/retention`, `currentOnly` query param default `true`) |
| `adapter/in/web/GlobalExceptionHandler.java`                                                                     | MODIFY (409 retention/version conflict, 400 invalid retention)                                                                                   |
| `adapter/out/persistence/DocumentJpaEntity.java`, `DocumentJpaRepository.java`, `DocumentRepositoryAdapter.java` | MODIFY (new columns, version queries, `is_current` transitions)                                                                                  |

### 6.6 MODIFY — bff & frontend (tight scope, §7.3)

| Path                                                                          | Change                                                                                                                    |
| ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `bff/src/main/java/it/enterprise/claims/bff/web/DashboardController.java`     | `TRACKED_STATUSES` (10, lifecycle order) + `OPEN_STATUSES` (+`APPRAISAL`, `SETTLEMENT`)                                   |
| `bff/src/test/java/it/enterprise/claims/bff/web/DashboardControllerTest.java` | expectations for the new lists                                                                                            |
| `frontend/src/types/claims.ts`                                                | `CLAIM_STATUSES` (10), `OPEN_STATUSES`, `STATUS_LABELS` (Perizia / Liquidazione / Archiviata), `ALLOWED_TRANSITIONS` (§3) |
| `frontend/src/components/ClaimStatusBadge.tsx`                                | `TONES`: `APPRAISAL: 'warning'`, `SETTLEMENT: 'info'`, `ARCHIVED: 'muted'`                                                |
| `frontend/src/components/ClaimStatusBadge.test.tsx`                           | one case per new status                                                                                                   |
| `frontend/src/styles/global.css`                                              | `.distribution__fill--appraisal`, `.distribution__fill--settlement`, `.distribution__fill--archived`                      |

### 6.7 CREATE/MODIFY — tests (§8)

| Path                                                                                                                                                                                              | Action                                                                                         |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `core-app/src/test/java/it/enterprise/claims/core/adapter/out/persistence/SchemaMigrationContractTest.java`                                                                                       | MODIFY (load V1 **and** V2, new entities in `ENTITIES`, 10-status parity for 4 CHECK families) |
| `core-app/src/test/java/it/enterprise/claims/core/adapter/out/persistence/ClaimStatusTransitionContractTest.java`                                                                                 | **CREATE** (enum ⇄ V2 seed parity, no self-transition, `ARCHIVED` terminal)                    |
| `core-app/src/test/java/it/enterprise/claims/core/adapter/out/persistence/ActorRepositoryAdapterTest.java`, `ClaimAssignmentPersistenceAdapterTest.java`, `AuditEventPersistenceAdapterTest.java` | **CREATE** (Mockito)                                                                           |
| `core-app/src/test/java/it/enterprise/claims/core/application/ActorServiceTest.java`, `ClaimAssignmentServiceTest.java`, `AuditRecorderTest.java`                                                 | **CREATE**                                                                                     |
| `core-app/src/test/java/it/enterprise/claims/core/application/ClaimServiceTest.java`                                                                                                              | MODIFY (new transitions, audit append, closed/archived timestamps, 409 on `APPROVED → CLOSED`) |
| `core-app/src/test/java/it/enterprise/claims/core/adapter/in/web/ActorControllerTest.java`, `ClaimAssignmentControllerTest.java`, `ClaimAuditEventControllerTest.java`                            | **CREATE** (`@WebMvcTest`)                                                                     |
| `core-app/src/test/java/it/enterprise/claims/core/adapter/in/web/ClaimControllerTest.java`                                                                                                        | MODIFY (actorId, `/documents` GET)                                                             |
| `document-service/src/test/java/it/enterprise/claims/documents/adapter/out/persistence/SchemaMigrationContractTest.java`                                                                          | MODIFY (V1+V2, new columns, partial unique index, jsonb-free)                                  |
| `document-service/src/test/java/it/enterprise/claims/documents/adapter/out/persistence/DocumentVersioningAdapterTest.java`                                                                        | **CREATE**                                                                                     |
| `document-service/src/test/java/it/enterprise/claims/documents/application/DocumentServiceTest.java`                                                                                              | MODIFY (new version, retention, legal hold)                                                    |
| `document-service/src/test/java/it/enterprise/claims/documents/adapter/in/web/DocumentControllerTest.java`                                                                                        | MODIFY (3 new endpoints)                                                                       |

### 6.8 MODIFY — OpenAPI snapshots (`docs/api/`), see §7 for the contract

`docs/api/core-app.openapi.yaml`, `docs/api/document-service.openapi.yaml`, `docs/api/bff.openapi.yaml`
→ `info.version: 0.2.0`.

### 6.9 MODIFY — version alignment (AC‑3, `docs/versioning.md`)

`VERSION` (`0.2.0`), `pom.xml` (root `<version>`), `core-app/pom.xml`,
`document-service/pom.xml`, `bff/pom.xml` (`<parent><version>`). Preferred command, if the Maven
repository is reachable: `./mvnw -N versions:set -DnewVersion=0.2.0 && ./mvnw -N versions:commit`;
otherwise edit the four POMs manually (the CI gate only checks `VERSION` == root POM version, but a
mismatched module parent breaks the reactor).

---

## 7. API contracts (OpenAPI 0.2.0)

Conventions already in force and to be respected: base `/api/v1` (core-app, document-service) and
`/bff/v1` (bff); `application/json`; errors `application/problem+json` (RFC 9457) with `title`,
`status`, `detail`, `instance`, `errors{field:message}`; pagination envelope
`{content, page, size, totalElements, totalPages}`; `page` clamped ≥ 0, `size` clamped 1…100.

### 7.1 core-app — new/changed paths

| Method | Path                                              | Request                                                          | Response                                                   | Errors                                                        |
| ------ | ------------------------------------------------- | ---------------------------------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------- |
| GET    | `/api/v1/roles`                                   | —                                                                | `200 [RoleResponse]`                                       | —                                                             |
| POST   | `/api/v1/actors`                                  | `ActorCreateRequest`                                             | `201 ActorResponse` + `Location`                           | 400, 409 duplicate `subjectRef`, 400 unknown role             |
| GET    | `/api/v1/actors`                                  | `page,size,sort`, `role?,actorType?,enabled?`                    | `200 ActorPageResponse`                                    | 400                                                           |
| GET    | `/api/v1/actors/{actorId}`                        | —                                                                | `200 ActorResponse`                                        | 404                                                           |
| PATCH  | `/api/v1/actors/{actorId}`                        | `ActorUpdateRequest`                                             | `200 ActorResponse`                                        | 400, 404                                                      |
| PUT    | `/api/v1/actors/{actorId}/roles`                  | `ActorRolesUpdateRequest`                                        | `200 ActorResponse` (full set, idempotent)                 | 400, 404                                                      |
| GET    | `/api/v1/claims/{claimId}/assignments`            | `activeOnly?=true`                                               | `200 [ClaimAssignmentResponse]`                            | 404                                                           |
| PUT    | `/api/v1/claims/{claimId}/assignments/{roleCode}` | `ClaimAssignmentRequest`                                         | `200 ClaimAssignmentResponse` (closes the previous holder) | 400, 404, 409                                                 |
| DELETE | `/api/v1/claims/{claimId}/assignments/{roleCode}` | —                                                                | `204`                                                      | 404                                                           |
| GET    | `/api/v1/claims/{claimId}/audit-events`           | `page,size,eventType?`                                           | `200 AuditEventPageResponse`                               | 404                                                           |
| GET    | `/api/v1/claims/{claimId}/documents`              | `includeUnlinked?=false`                                         | `200 [ClaimDocumentLinkResponse]`                          | 404                                                           |
| PATCH  | `/api/v1/claims/{claimId}/status`                 | **changed**: `ClaimStatusUpdateRequest{status, note?, actorId?}` | `200 ClaimResponse`                                        | 400, 404, 409 (illegal transition **or** unknown actor → 404) |

Changed schemas:

- `ClaimStatus` enum → 10 values (§3), description carries the IT mapping.
- `ClaimResponse` `+closedAt`, `+archivedAt` (nullable).
- `ClaimStatusHistoryResponse` `+actorId`, `+actorDisplayName` (nullable — historical rows).
- New: `RoleResponse{code,name,description}`, `ActorCreateRequest{subjectRef,displayName,email?,actorType,roleCodes?}`,
  `ActorUpdateRequest{displayName?,email?,enabled?}`, `ActorRolesUpdateRequest{roleCodes: string[]}`,
  `ActorResponse{id,subjectRef,displayName,email,actorType,enabled,roleCodes[],createdAt,updatedAt}`,
  `ActorPageResponse`, `ClaimAssignmentRequest{actorId}`, `ClaimAssignmentResponse{id,claimId,actorId,actorDisplayName,roleCode,assignedAt,assignedByActorId,unassignedAt}`,
  `AuditEventResponse{id,claimId,actorId,eventType,entityType,entityId,outcome,occurredAt,requestId,details}`,
  `AuditEventPageResponse`, `ClaimDocumentLinkResponse{documentId,linkedAt,linkedByActorId,unlinkedAt}`.

Validation rules to mirror in `jakarta.validation` **and** the OpenAPI schema:
`subjectRef` `@NotBlank @Size(3,128) @Pattern("^[A-Za-z0-9._:@|/-]{3,128}$")`;
`displayName` `@NotBlank @Size(max=200)`; `email` `@Email @Size(max=255)`;
`actorType` `@NotNull`; `roleCodes` `@Size(max=10)`; `note` `@Size(max=500)`;
`details` opaque object, `maxProperties` documented but not enforced.

### 7.2 document-service — new/changed paths

| Method | Path                                       | Request                                                       | Response                                                      | Errors                             |
| ------ | ------------------------------------------ | ------------------------------------------------------------- | ------------------------------------------------------------- | ---------------------------------- |
| GET    | `/api/v1/documents?claimId=`               | **changed**: `+currentOnly` (default `true`)                  | `200 DocumentPageResponse` (current versions only by default) | 400                                |
| GET    | `/api/v1/documents/{documentId}/versions`  | —                                                             | `200 [DocumentVersionResponse]` (newest first)                | 404                                |
| POST   | `/api/v1/documents/{documentId}/versions`  | `multipart/form-data: file`, `documentType?`                  | `201 DocumentResponse` + `Location`                           | 400, 404, 409 (concurrent version) |
| PATCH  | `/api/v1/documents/{documentId}/retention` | `DocumentRetentionUpdateRequest{retentionUntil?, legalHold?}` | `200 DocumentResponse`                                        | 400 (past date), 404               |
| DELETE | `/api/v1/documents/{documentId}`           | —                                                             | `204` — **changed**: `409` when `legalHold = true`            | 404, 409                           |

Changed schemas: `DocumentResponse` `+documentKey`, `+versionNumber`, `+isCurrent`, `+keyRef`(nullable),
`+retentionUntil`(nullable, `format: date`), `+legalHold`; new
`DocumentVersionResponse{id,documentKey,versionNumber,isCurrent,fileName,documentType,sizeBytes,checksumSha256,uploadedAt,deletedAt}`,
`DocumentRetentionUpdateRequest{retentionUntil?,legalHold?}`.
`storageKey` and `keyRef` policy: `storageKey` is **never** exposed (unchanged rule); `keyRef` is
exposed because it is an opaque non-secret alias — document this explicitly in the schema description.

### 7.3 bff & frontend delta

- `bff.openapi.yaml`: `ClaimStatus` enum 10 values, dashboard description ("open" = `DRAFT + SUBMITTED +
UNDER_REVIEW + INTEGRATION_REQUESTED + APPRAISAL + SETTLEMENT`), `info.version: 0.2.0`.
- No new BFF endpoints in this task (see §11 OUT OF SCOPE): the UI keeps consuming the aggregated
  claim + documents; the frontend change is limited to the status vocabulary.

---

## 8. Test strategy — green **without** PostgreSQL (A8)

| Level                   | Class                                                                                                                                                         | Proves                                                                                                                                                         |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Schema ⇄ entity (text)  | `SchemaMigrationContractTest` (both modules, **extended**)                                                                                                    | every `@Table`/`@Column`/`@UniqueConstraint` of every new entity exists in `V1+V2`; `length` parity; `ClaimStatus` == the 4 CHECK families                     |
| State machine ⇄ DB      | `ClaimStatusTransitionContractTest` (**new**, core-app)                                                                                                       | the 17 seeded pairs == `ClaimStatus.allowedTransitions()` (set equality), no self-transition, exactly one terminal state, no rule referencing an unknown value |
| Repository adapters     | `ActorRepositoryAdapterTest`, `ClaimAssignmentPersistenceAdapterTest`, `AuditEventPersistenceAdapterTest`, `DocumentVersioningAdapterTest` (**new**, Mockito) | entity ⇄ domain mapping, soft-delete filtering, "close previous active assignment", `is_current` swap, append-only port has no mutators                        |
| Application             | `ActorServiceTest`, `ClaimAssignmentServiceTest`, `AuditRecorderTest`, `DocumentServiceTest`, `ClaimServiceTest`                                              | 409 duplicate, 400 unknown role, 404 unknown actor, retention validation, audit row per state change                                                           |
| Web                     | `@WebMvcTest` controllers                                                                                                                                     | status codes, payload shape, `ProblemDetail` mapping                                                                                                           |
| Executable SQL contract | `infra/db/verify/01-assert-schema.sql` (**extended**)                                                                                                         | _when a PostgreSQL is available_: the migrations are accepted and the objects/constraints/indexes/transition pairs are real                                    |

Commands (must be green before hand-off):

```bash
./mvnw -B -ntp -pl core-app,document-service,bff verify
cd frontend && npm run lint && npm run format:check && npm run test:run && npm run build
./frontend/node_modules/.bin/prettier --check docs infra README.md   # from repo root
```

> Honest note to keep in the docs/PR: without a PostgreSQL instance (none in this container) the
> `ddl-auto=validate` startup check and `01-assert-schema.sql` remain **unproven locally**; the
> contract tests close the entity ⇄ SQL-text half only. Do not claim otherwise.

---

## 9. Implementation waves (order is mandatory)

| Wave                                   | Owner             | Files                                                                                                                                              | Exit criteria                                                                          |
| -------------------------------------- | ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| **W0 — Schema**                        | Data Architecture | §6.1 (2 migrations), `infra/db/verify/01-assert-schema.sql`, `infra/db/verify/README.md`, `docs/db/README.md`, `ClaimStatusTransitionContractTest` | migrations parse; contract/parity tests green; assert script updated (counts included) |
| **W1 — core-app domain + persistence** | Backend           | `ClaimStatus`, `Claim`, `ClaimStatusHistory`, 6 domain types, 4 ports, entities/repos/adapters (§6.4)                                              | entities compile, `SchemaMigrationContractTest` extended & green, adapter tests green  |
| **W2 — core-app application + web**    | Backend           | services, DTOs, exceptions, 4 controllers, `GlobalExceptionHandler`, `ClaimService` audit wiring                                                   | `@WebMvcTest` + service tests green; `409/404/400` mapped                              |
| **W3 — document-service**              | Backend           | migration consumers: `Document`, ports, `DocumentService`, DTOs, controller, adapter                                                               | version chain + retention + legal hold tests green; `409` on delete on hold            |
| **W4 — Contracts & docs**              | Architect/Backend | 3 `docs/api/*.openapi.yaml` (0.2.0), `docs/architecture/adr/0013-*.md`, `docs/architecture/README.md`, 2 module READMEs                            | YAML parse gate + Prettier green; enum parity with `ClaimStatus`                       |
| **W5 — Alignment & release prep**      | Full-stack        | `bff` dashboard lists + test, 3 frontend files + test, `VERSION` + 4 POMs to `0.2.0`                                                               | full `./mvnw verify` + frontend pipeline + Prettier green                              |

Hand-off rule: a wave is "done" only when the checks of its Exit column and the global DoD (§2) pass.
W0 and W1 must land together in the same commit set (the contract test fails otherwise).

---

## 10. Risks & mitigations

| #   | Risk                                                                                                                                       | Impact          | Mitigation                                                                                                                                                                                                                                                                                                 |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------ | --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1  | `ddl-auto: validate` cannot be executed here (no PostgreSQL) — a `jsonb`/`timestamptz`/`boolean` mapping mismatch surfaces only at startup | Runtime         | Keep mappings to the documented Hibernate-6 idioms (`@JdbcTypeCode(SqlTypes.JSON)` for `details`, `OffsetDateTime`, `LocalDate`, `Boolean`); contract test asserts the SQL type of every column. Fallback ready: `details varchar(4000)` + `pg_input_is_valid(details,'jsonb')` CHECK, documented in DD‑11 |
| R2  | Composite FK `(from_status,to_status)` fails if legacy history rows violate the new rules                                                  | Migration abort | Explicit `raise exception` guard + hint (§4.1 step 7); no silent relaxation. Fresh DBs are unaffected                                                                                                                                                                                                      |
| R3  | Adding 3 enum values is a client-visible change (UI, BFF, external consumers)                                                              | UX/compat       | Additive only, UI already degrades gracefully; labels + transitions shipped in the same PR (W5); OpenAPI `info.version` 0.2.0                                                                                                                                                                              |
| R4  | `APPROVED → CLOSED` removal breaks in-flight approved claims                                                                               | Behavioural     | No production data (0.1.x, phase 1); if a dev DB has such rows, move them to `SETTLEMENT` first — documented in `docs/db/README.md` §7                                                                                                                                                                     |
| R5  | Scope is large (2 migrations, ~45 Java files, 3 contracts)                                                                                 | Delivery        | Waves W0‑W5 with per-wave exit criteria; §11 lists what may be deferred without breaking an AC                                                                                                                                                                                                             |
| R6  | Checkstyle/Spotless/Prettier gates                                                                                                         | Build red       | Run `./mvnw -B spotless:apply` and `./frontend/node_modules/.bin/prettier --write docs infra` before hand-off                                                                                                                                                                                              |
| R7  | Maven `versions:set` needs the plugin online                                                                                               | Release prep    | Manual POM edits accepted (§6.9)                                                                                                                                                                                                                                                                           |

---

## 11. SKIP analysis

- **BACKEND_SKIP: NO** — `core-app` (actors/roles/assignments/audit/state machine) and
  `document-service` (versioning/retention) are the core of the ticket; `bff` changes only its
  dashboard status lists.
- **DB_SKIP: NO** — 2 new Flyway migrations, 2 schemas touched, **6 new tables in `coreapp`**
  (`roles`, `actors`, `actor_roles`, `claim_assignments`, `audit_events`, `claim_status_transition`) and
  **13 new columns** across `claims` (2), `claim_status_history` (1), `claim_documents` (2) and
  `documents` (7), plus `infra/db/verify/01-assert-schema.sql` and `docs/db/README.md`.
- **FRONTEND_SKIP: NO (minimal)** — 3 files only (`types/claims.ts`, `ClaimStatusBadge.tsx`,
  `global.css` + 1 test) to keep the Italian labels/transitions in sync with the 3 new states. No new
  page, no new route, no new form, no new API client (`src/api/*` untouched).
- **INFRA_SKIP: YES (mostly)** — no Dockerfile, `docker-compose.yml`, Kustomize or script change. The
  only infra-adjacent item is the _proposal_ (not implemented here, DevOps-owned) to add the already
  documented `db-schema-contract` CI job with the two V2 files in its `psql` loop.
- **BFF_SKIP: almost** — no new BFF endpoint, no DTO shape change; only the two hard-coded status
  lists in `DashboardController` + `bff.openapi.yaml`.

### Explicitly OUT OF SCOPE (do not implement in this task)

1. OIDC/authentication and authorization (ADR‑0005): `actorId` is caller-asserted (A9).
2. Physical retention/purge job, storage-tiering, S3 adapter, KMS integration for `key_ref` (only the
   reference column is modelled).
3. New BFF endpoints for actors/assignments/audit and any new UI screen.
4. `document-service` audit table (audit lives only in `coreapp`, DD‑16).
5. Testcontainers / embedded PostgreSQL in the default build.
6. Claim soft delete (`claims.deleted_at`) — claims are archived, not deleted, by design.
7. Seeding of business demo data (only reference data: `roles`, `claim_status_transition`).
