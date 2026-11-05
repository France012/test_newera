# TK-64FBC8 — Closure blueprint (domain model & DB schema, pratiche assicurative)

> **Audience**: backend developers (core-app, document-service), QA, release manager.
> **Owner**: Architect. This document **supersedes** `docs/plan/TK-64FBC8-plan.md` for the *remaining*
> work only: the greenfield design in that plan is already implemented and must **not** be redone.
> Every claim below was verified by executing commands in the workspace on 2026-11-05
> (evidence quoted per section).

---

## 0. Verdict — one-line summary

The **domain model, Flyway migrations, JPA adapters, audit trail, seeded state machine, DB contract
script and module docs are DONE and consistent**. The **delivery is not shippable** because:

| # | Gate | Status | Evidence |
|---|------|--------|----------|
| 1 | `core-app` test sources compile | ❌ **RED** | `mvn test` → `COMPILATION ERROR ClaimControllerTest.java:[166,12]` |
| 2 | `document-service` tests | ❌ **RED** | `Tests run: 31, Failures: 1, Errors: 3` |
| 3 | Spotless (format gate) | ❌ **RED** | 3 files with violations in `core-app/src/test` |
| 4 | Published OpenAPI contract | ❌ **STALE** | `docs/api/*.openapi.yaml` `info.version: 0.1.0`, no `/actors`, `/roles`, `/assignments`, `/audit-events`, `/versions`, `/retention`; missing `closedAt`, `archivedAt`, `actorId`, `documentKey`, `retentionUntil`, … |
| 5 | Version bump to `0.2.0` | ❌ **NOT DONE** | `VERSION` = `0.1.0`, 4 POMs = `0.1.0` |
| 6 | DB schema / migrations | ✅ DONE — **DB_SKIP** | `V1`+`V2` in both modules, idempotent, 200 assertions in `infra/db/verify/01-assert-schema.sql` |
| 7 | BFF + frontend status mirroring | ✅ DONE — no action | `DashboardController` (10 states) and `frontend/src/types/claims.ts` (10 states) already aligned |

**So this task is a *closure* task**: fix the test suites, un-break the format gate, republish the
OpenAPI contract at `0.2.0`, align the version. **No new migration, no new entity, no new endpoint,
no UI work.**

---

## 1. Discovery — current state report

### 1.1 Tech stack (verified)

| Layer | Technology | Notes |
|---|---|---|
| Runtime | Java 21 (`openjdk 21.0.11`), Spring Boot 3.3.5 | `pom.xml` parent, `java.version=21` |
| Build | Maven multi-module (`claims-platform` 0.1.0) → `core-app`, `document-service`, `bff` | `./mvnw`, Spotless 2.43 + google-java-format 1.22, Checkstyle (max line 120), JaCoCo 0.8.12, CycloneDX |
| Architecture | Hexagonal (ADR-0002): `domain/model`, `domain/port`, `application`, `application/dto`, `adapter/in/web`, `adapter/out/persistence` | domain records are JPA/JSON-annotation free |
| DB | PostgreSQL 16, database-per-service, **Flyway 10** forward-only | schemas `coreapp` (core-app) and `documents` (document-service) |
| Contract | Hand-written OpenAPI 3.0.3 snapshots in `docs/api/` + springdoc runtime `/v3/api-docs` (ADR-0009 gate) | CI validates they parse |
| Frontend | React + TypeScript + Vite (`frontend/`), consumes BFF only | already 10-state aware |
| Ports | core-app `8180`, document-service `8181`, BFF `8185`, Postgres host port `5442` | non-standard by design |

### 1.2 Domain model — already implemented (DO NOT recreate)

`core-app/src/main/java/it/enterprise/claims/core/domain/model/`
`Claim.java` (aggregate root) · `ClaimStatus.java` (state machine) · `ClaimStatusHistory.java` ·
`ClaimDocumentLink.java` · `Customer.java` · `Policy.java` · `Actor.java` · `ActorType.java` ·
`Role.java` · `ClaimAssignment.java` · `AuditEvent.java` · `AuditEventType.java`

Ports (`domain/port/`): `ClaimRepositoryPort`, `PolicyRepositoryPort`, `ActorRepositoryPort`,
`RoleRepositoryPort`, `ClaimAssignmentPort`, `ClaimDocumentPort`, `AuditEventPort`.

Application (`application/`): `ClaimService` (13KB), `ActorService`, `RoleService`,
`ClaimAssignmentService`, `PolicyService`, `AuditRecorder` + 19 DTO records
(`ClaimResponse`, `ClaimCreateRequest`, `ClaimStatusUpdateRequest`, `ActorResponse`,
`ActorCreateRequest`, `ActorUpdateRequest`, `ActorRolesUpdateRequest`, `ActorPageResponse`,
`RoleResponse`, `ClaimAssignmentRequest`, `ClaimAssignmentResponse`, `AuditEventResponse`,
`AuditEventPageResponse`, `ClaimPageResponse`, `ClaimStatusHistoryResponse`,
`ClaimDocumentLinkResponse`, `DocumentIdsRequest`, `PolicyResponse`, `PolicyPageResponse`).

Persistence adapters: 30 classes under `adapter/out/persistence/` (JPA entity + Spring Data repo +
`*PersistenceAdapter` per aggregate, incl. `ActorRoleId` and `ClaimDocumentId` composite ids).

`document-service`: `Document` + `DocumentVersion` domain, `DocumentService` with
`uploadNewVersion` / `findVersions` / `updateRetention` / `delete`, 6 typed exceptions.

### 1.3 State machine (`ClaimStatus`, single source of truth)

10 values in lifecycle order — task requirement `aperta…archiviata` mapped as:

| ticket (IT) | persisted value |
|---|---|
| aperta | `DRAFT` |
| presa in carico | `SUBMITTED` |
| in istruttoria | `UNDER_REVIEW` |
| attesa integrazione | `INTEGRATION_REQUESTED` |
| perizia | `APPRAISAL` |
| delibera | `APPROVED` / `REJECTED` |
| liquidazione | `SETTLEMENT` |
| chiusa | `CLOSED` |
| archiviata | `ARCHIVED` (terminal) |

Transitions: `DRAFT→SUBMITTED→UNDER_REVIEW→{INTEGRATION_REQUESTED,APPRAISAL,APPROVED,REJECTED}`,
`INTEGRATION_REQUESTED→UNDER_REVIEW`, `APPRAISAL→{UNDER_REVIEW,APPROVED,REJECTED}`,
`APPROVED→SETTLEMENT→CLOSED→ARCHIVED`, `REJECTED→CLOSED`. **`APPROVED→CLOSED` is forbidden**
(liquidazione cannot be skipped).

Enforced **twice**: application (`409 Illegal claim status transition` before the write) **and**
database (`coreapp.claim_status_transition` seed + composite FK `fk_claim_status_history_transition`).

### 1.4 DB schema (verified, complete)

`core-app/src/main/resources/db/migration/V1__init_claims.sql` (immutable): `coreapp.customers`,
`policies`, `claims`, `claim_status_history`, `claim_documents`.
`core-app/.../V2__claim_workflow_actors_audit.sql` (442 lines, idempotent): `roles` (seeded 5 roles),
`actors` (soft delete `deleted_at`, `version` optimistic lock), `actor_roles` (revoke via
`revoked_at`), `claim_assignments` (one active holder per `(claim, role)`), `audit_events`
(append-only), `claim_status_transition` (14 pairs) + `closed_at`/`archived_at` with their CHECKs and
partial indexes.

`document-service/.../V1__init_documents.sql` + `V2__document_versioning_retention.sql`:
`document_key` (logical id), `version_number`, `is_current` (partial unique), `supersedes_id`,
`key_ref` (KMS alias, non-secret), `retention_until`, `legal_hold`, backfill before `set not null`
(rolling-deploy safe). No cross-service FK to `coreapp.claims` (DD-5).

### 1.5 Guards already in place

* `core-app/.../adapter/out/persistence/SchemaMigrationContractTest.java` (9 tests, no DB): parses V1+V2
  and compares to the 10 JPA entities; asserts `ClaimStatus` parities.
* `document-service/.../SchemaMigrationContractTest.java` (11 tests): same for `documents`.
* `core-app/.../ClaimStatusTransitionContractTest.java` (7 tests): enum ⇄ `claim_status_transition` seed.
* `core-app/.../PersistenceEntityLifecycleTest.java` (9 tests) / `DocumentEntityLifecycleTest` (6).
* `infra/db/verify/01-assert-schema.sql`: 200 runtime assertions over 12 tables (needs a live PG).

---

## 2. Requirements — acceptance criteria & Definition of Done

The original ACs (`docs/plan/TK-64FBC8-plan.md` §2) are partially satisfied. Restated with the
**exact verification** that must pass before the ticket closes:

| AC | Requirement | Verified by | State today |
|---|---|---|---|
| **AC-1** | Migrazioni idempotenti e applicabili da zero | `./mvnw -B -o -pl core-app,document-service test` → `SchemaMigrationContractTest` (parses V1+V2) **and** optional live check: `psql … -f infra/db/verify/01-assert-schema.sql` → `SCHEMA CONTRACT OK / 200` | ✅ schema OK, ⚠️ build RED for other reasons |
| **AC-2** | Vincoli di integrità + transizioni applicati a DB **e** applicazione | `ClaimStatusTransitionContractTest` (7), `SchemaMigrationContractTest` constraint blocks, `ClaimServiceTest` (409 cases) | ⚠️ test sources do not compile |
| **AC-3** | Contratto OpenAPI pubblicato **e versionato** | `docs/api/core-app.openapi.yaml` + `document-service.openapi.yaml` contain all implemented paths/schemas and `info.version: 0.2.0`; both parse as YAML (ADR-0009) | ❌ **stale at 0.1.0, 6 path groups missing** |
| **AC-4** | Test di repository/schema verdi | `./mvnw -B -o verify` exits 0 with Spotless + Checkstyle | ❌ **RED** |
| **AC-5** | Soft delete rispettato su ogni read path | `PersistenceEntityLifecycleTest` + repository adapter tests | ⚠️ not yet executed |
| **AC-6** | Versioning documentale + retention/legal hold | `document-service` `SchemaMigrationContractTest` (11) + `DocumentServiceTest` (8) | ❌ 1 failure, 3 errors |

**Definition of Done (binary, in this order):**

1. `./mvnw -B -o -pl core-app test` → `BUILD SUCCESS`, 0 failures, and the 6 previously-never-compiled
   test classes (`ActorControllerTest`, `ClaimAssignmentControllerTest`, `ClaimAuditEventControllerTest`,
   `ActorServiceTest`, `AuditRecorderTest`, `ClaimAssignmentServiceTest`) appear in
   `core-app/target/surefire-reports/`.
2. `./mvnw -B -o -pl document-service test` → `BUILD SUCCESS`, 31+ tests, 0 failures/errors.
3. `./mvnw -B -o verify` (all 3 modules) → `BUILD SUCCESS` (includes `spotless:check` + `checkstyle:check`).
4. `docs/api/core-app.openapi.yaml` and `docs/api/document-service.openapi.yaml` at `info.version: 0.2.0`
   with §6 content, both parse (`python3 -c "import yaml,sys;[yaml.safe_load(open(f)) for f in sys.argv[1:]]" docs/api/*.yaml`).
5. `VERSION`, root `pom.xml`, `core-app/pom.xml`, `document-service/pom.xml`, `bff/pom.xml` all `0.2.0`
   (CI gate `Version consistency` requires `VERSION` == root pom).
6. No file under `core-app/src/main/resources/db/migration/` or
   `document-service/src/main/resources/db/migration/` is modified (V1/V2 are immutable).
7. `CHANGELOG.md` untouched (semantic-release owns it); commit message is Conventional
   (`fix(TK-64FBC8): …` / `feat(TK-64FBC8): …`).

---

## 3. Verified gap list — the ONLY work left

> Run everything from the workspace root. `-o` (offline) works: `~/.m2` is populated.

### GAP-1 — 🔴 BLOCKER: `core-app` test sources do not compile

```
[ERROR] .../adapter/in/web/ClaimControllerTest.java:[166,12] constructor ClaimResponse … cannot be applied
  required: UUID,String,String,UUID,String,ClaimStatus,String,LocalDate,OffsetDateTime,OffsetDateTime,List<UUID>,OffsetDateTime,OffsetDateTime
  found:    UUID,String,String,UUID,String,ClaimStatus,String,LocalDate,OffsetDateTime,OffsetDateTime,List<Object>
```

**Cause**: `ClaimResponse` gained `closedAt` and `archivedAt` (13 components);
the test helper still builds the old 12-arg form.

**File**: `core-app/src/test/java/it/enterprise/claims/core/adapter/in/web/ClaimControllerTest.java`
**Location**: private helper `claimResponse()` (lines ≈ 165-176, the `new ClaimResponse(...)` ending with `List.of());`)
**Change**: pass the two new trailing components, in record order, as `null` (a `DRAFT` claim is
neither closed nor archived). Keep `List.of()` for `documentIds` — the inferred `List<Object>`
disappears once the arity matches. Do **not** change `ClaimResponse`.

**This unblocks the compilation of the 6 never-executed test classes**, which will then run for the
first time and may reveal further assertion drift: expect a second wave and fix assertions (never
weaken them; the production behaviour is the contract).

### GAP-2 — 🔴 BLOCKER: `document-service` `DocumentControllerTest` (3 errors)

```
NoSuchMethod 'void DocumentResponse.<init>(UUID,UUID,String,String,Long,String,String,OffsetDateTime,Integer)'
NoSuchMethod 'DocumentPageResponse DocumentService.findByClaimId(UUID, Pageable)'
```

**File**: `document-service/src/test/java/it/enterprise/claims/documents/adapter/in/web/DocumentControllerTest.java`

* `documentResponse()` helper (line ≈ 141): `DocumentResponse` now has **15** components, in order
  `id, documentKey, claimId, fileName, contentType, sizeBytes, documentType, checksumSha256,
  versionNumber, isCurrent, keyRef, retentionUntil, legalHold, uploadedAt, version`.
  Rebuild the helper with the new arity (use `DOCUMENT_ID` for `documentKey`, `1`/`true` for
  `versionNumber`/`isCurrent`; the remaining new fields may be `null`/`false` as decided by the test intent).
* `listReturnsPage` (line ≈ 113): the stub must match the new signature
  `findByClaimId(claimId, currentOnly, pageable)` → use `eq(CLAIM_ID), anyBoolean(), any(Pageable.class)`.

### GAP-3 — 🔴 BLOCKER: `DocumentServiceTest.findByClaimIdRequiresClaimId` (1 failure)

```
DocumentServiceTest.findByClaimIdRequiresClaimId:153
```

**File**: `document-service/src/test/java/it/enterprise/claims/documents/application/DocumentServiceTest.java`
**Location**: lines 151-155.
**Change**: call the current 3-arg overload `documentService.findByClaimId(null, true, PageRequest.of(0, 20))`.
The expected exception `InvalidDocumentException` with message containing `claimId` is **still correct**
(`DocumentService.java:194` → `"claimId is required"`); only the invocation is stale.

### GAP-4 — 🟠 Spotless format gate red (would fail `verify`/CI)

```
The following files had format violations:
  src/test/java/it/enterprise/claims/core/adapter/in/web/ActorControllerTest.java
  src/test/java/it/enterprise/claims/core/application/ClaimServiceTest.java
  src/test/java/it/enterprise/claims/core/application/ClaimAssignmentServiceTest.java
```

**Fix**: `./mvnw -B spotless:apply` from the root (do **not** hand-format), then re-run `spotless:check`.
Fix GAP-1/2/3 **before** applying, so Spotless formats the corrected code only once.

### GAP-5 — 🟠 `docs/api/core-app.openapi.yaml` is stale (AC-3)

Missing vs. `adapter/in/web/*.java` (verified by grepping `@*Mapping`):

| Implemented but undocumented |
|---|
| `GET /api/v1/roles` (`RoleController`) |
| `GET,POST /api/v1/actors`; `GET,PATCH /api/v1/actors/{actorId}`; `PUT /api/v1/actors/{actorId}/roles` (`ActorController`) |
| `GET /api/v1/claims/{claimId}/assignments`; `PUT,DELETE /api/v1/claims/{claimId}/assignments/{roleCode}` (`ClaimAssignmentController`) |
| `GET /api/v1/claims/{claimId}/audit-events` (`ClaimAuditEventController`) |
| `DELETE /api/v1/claims/{claimId}/documents/{documentId}` (declared at `ClaimController:162`) |
| `ClaimResponse.closedAt`, `ClaimResponse.archivedAt` — **breaking drift**, the snapshot does not match the DTO |
| `ClaimStatusUpdateRequest.actorId` |
| `info.version: 0.1.0` → `0.2.0` |

### GAP-6 — 🟠 `docs/api/document-service.openapi.yaml` is stale (AC-3)

Missing: `GET /api/v1/documents/{documentId}/versions`, `POST /api/v1/documents/{documentId}/versions`
(multipart), `PATCH /api/v1/documents/{documentId}/retention`; `DocumentResponse` new fields
(`documentKey`, `versionNumber`, `isCurrent`, `keyRef`, `retentionUntil`, `legalHold`) plus the schemas
`DocumentVersionResponse` and `DocumentRetentionUpdateRequest`; `info.version` → `0.2.0`.
(`DELETE /api/v1/documents/{documentId}` is already documented.)

### GAP-7 — 🟡 version not bumped to `0.2.0` (mandated by `docs/plan/TK-64FBC8-plan.md` W5)

`VERSION` = `0.1.0`, and `pom.xml`, `core-app/pom.xml`, `document-service/pom.xml`, `bff/pom.xml` = `0.1.0`.
Procedure (from `docs/versioning.md` §"Manual bump", must be **atomic**):

```bash
echo "0.2.0" > VERSION
./mvnw -N versions:set -DnewVersion=0.2.0 && ./mvnw -N versions:commit
# then update the module <version>0.2.0</version> lines (they are explicit, not inherited-only)
./mvnw -B -o verify
```

CI job `Version consistency (VERSION == root pom version)` fails if `VERSION` and the root POM diverge.

### GAP-8 — 🟡 (optional, docs) ADR for the state machine is missing

`docs/architecture/adr/` stops at `0012`; the original plan's W4 listed `0013-claim-workflow-state-machine-and-audit.md`.
Decisions A1-A15 are already recorded in `docs/plan/TK-64FBC8-plan.md` §0 and `docs/db/README.md` §5, so
this is *nice-to-have*; if written, keep it ≤ 40 lines and link it from `docs/architecture/README.md`.

---

## 4. Locked decisions — do NOT reopen

| ID | Decision |
|---|---|
| D1 | Italian domain names are mapped to English identifiers (`pratica`→`Claim`, `documento`→`Document`, `attore`→`Actor`, `ruolo`→`Role`, `evento_audit`→`AuditEvent`). Mapping table lives in `ClaimStatus` javadoc and `docs/db/README.md`. |
| D2 | `V1__*.sql` is **immutable**; all TK-64FBC8 DDL is in `V2__*.sql`. No V3 is needed — the schema is complete. |
| D3 | No DB triggers, no native PG enums, no DB-generated UUIDs; timestamps via JPA callbacks; `@Version` for optimistic locking. |
| D4 | State machine enforced twice (service → 409, DB → composite FK on `claim_status_transition`). |
| D5 | `claim_documents`/`documents.claim_id` are **logical** cross-service references — never a cross-schema FK. |
| D6 | Audit is append-only (no UPDATE/DELETE path); reads are newest-first (`idx_audit_events_claim`). |
| D7 | Soft delete everywhere (`deleted_at`, `revoked_at`, `unassigned_at`); every read filters it. |
| D8 | No authentication in phase 1 (ADR-0005); `actorId`/`subjectRef` are caller-asserted. |
| D9 | `storage_key` is never exposed by the API; `key_ref` is (non-secret KMS alias). |
| D10 | Enum changes require **both** the enum and a migration; parity is asserted by the contract tests. |

---

## 5. File inventory — exact paths inside WORKSPACE ROOT

### 5.1 MODIFY (all inside `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`)

| # | Path | Change |
|---|---|---|
| 1 | `core-app/src/test/java/it/enterprise/claims/core/adapter/in/web/ClaimControllerTest.java` | GAP-1: helper `claimResponse()` → 13 components |
| 2 | `document-service/src/test/java/it/enterprise/claims/documents/adapter/in/web/DocumentControllerTest.java` | GAP-2: `documentResponse()` → 15 components; re-stub `findByClaimId` |
| 3 | `document-service/src/test/java/it/enterprise/claims/documents/application/DocumentServiceTest.java` | GAP-3: 3-arg `findByClaimId(null, true, PageRequest.of(0,20))` |
| 4 | `core-app/src/test/java/it/enterprise/claims/core/adapter/in/web/ActorControllerTest.java` | GAP-4: `spotless:apply` only |
| 5 | `core-app/src/test/java/it/enterprise/claims/core/application/ClaimServiceTest.java` | GAP-4: `spotless:apply` only |
| 6 | `core-app/src/test/java/it/enterprise/claims/core/application/ClaimAssignmentServiceTest.java` | GAP-4: `spotless:apply` only |
| 7 | `docs/api/core-app.openapi.yaml` | GAP-5: add paths/schemas of §6.1, `info.version: 0.2.0` |
| 8 | `docs/api/document-service.openapi.yaml` | GAP-6: add paths/schemas of §6.2, `info.version: 0.2.0` |
| 9 | `VERSION` | GAP-7: `0.2.0` |
| 10 | `pom.xml`, `core-app/pom.xml`, `document-service/pom.xml`, `bff/pom.xml` | GAP-7: `<version>0.2.0</version>` |
| 11 | *(optional)* `docs/architecture/adr/0013-claim-workflow-state-machine-and-audit.md` + `docs/architecture/README.md` | GAP-8 |

### 5.2 CREATE

**None.** No new Java class, migration, entity, endpoint, DTO, page or component is required by this
task. If a developer believes a new file is needed, it must first be justified against §3 — the
domain surface is complete.

### 5.3 MUST NOT TOUCH

`core-app/src/main/resources/db/migration/**` · `document-service/src/main/resources/db/migration/**` ·
`infra/db/verify/01-assert-schema.sql` · `infra/db/init/01-init-schemas.sql` · `docs/db/README.md`
(already documents all 12 tables) · `frontend/**` · `bff/src/main/**` · `CHANGELOG.md`.

---

## 6. API contracts to publish (OpenAPI 3.0.3, `info.version: 0.2.0`)

Error model is unchanged everywhere: RFC 9457 `application/problem+json` from `GlobalExceptionHandler`
(`type: about:blank`, `instance` = request URI). Reuse the existing `#/components/responses/*` refs.

### 6.1 `docs/api/core-app.openapi.yaml`

Add tag `Actors and roles` and tag `Audit`; append these path items:

```yaml
  /api/v1/roles:
    get:                                  # operationId: listRoles, tags: [Actors and roles]
      responses:
        '200': { content: { application/json: { schema: { type: array, items: { $ref: '#/components/schemas/RoleResponse' } } } } }

  /api/v1/actors:
    get:                                  # operationId: listActors
      parameters:                         # role (code), actorType, enabled, Page, Size, sort (default displayName,asc)
      responses: { '200': ActorPageResponse, '400': BadRequest }
    post:                                 # operationId: registerActor, consumes application/json
      requestBody: { required: true, content: { application/json: { schema: ActorCreateRequest } } }
      responses:
        '201': { headers: { Location: { schema: { type: string, format: uri } } }, content: ActorResponse }
        '400': BadRequest                 # "Validation failed" / "Malformed request body" / "Unknown role"
        '409': Conflict                   # title "Actor already registered" (duplicate subjectRef)

  /api/v1/actors/{actorId}:
    get:                                  # 200 ActorResponse, 404 NotFound ("Actor not found")
    patch:                                # ActorUpdateRequest (null = untouched) → 200 ActorResponse, 400, 404

  /api/v1/actors/{actorId}/roles:
    put:                                  # ActorRolesUpdateRequest (full replacement) → 200 ActorResponse, 400 "Unknown role", 404

  /api/v1/claims/{claimId}/assignments:
    get:                                  # parameter activeOnly (boolean, default true) → 200 array<ClaimAssignmentResponse>, 404

  /api/v1/claims/{claimId}/assignments/{roleCode}:
    put:                                  # ClaimAssignmentRequest {actorId} → 200 ClaimAssignmentResponse
                                          # 400 "Unknown role", 404 "Claim/Actor not found", 409 "Invalid assignment" (disabled actor)
    delete:                               # → 204 (idempotent), 404 "Claim not found"

  /api/v1/claims/{claimId}/audit-events:
    get:                                  # eventType (AuditEventType), Page, Size (clamped to 100)
                                          # → 200 AuditEventPageResponse, 400, 404 "Claim not found"

  /api/v1/claims/{claimId}/documents/{documentId}:
    delete:                               # → 204, 404; documented behaviour: unlink only, document-service stays owner
```

> **Status-code truth source**: `core-app/.../adapter/in/web/GlobalExceptionHandler.java`
> → 404 `Claim not found` / `Policy not found` / `Actor not found`; 409 `Actor already registered`
> (`DuplicateActorException`); 400 `Unknown role` (`UnknownRoleException`); 409 `Invalid assignment`
> (`InvalidAssignmentException`); 409 `Illegal claim status transition`; 400 `Invalid claim request` /
> `Validation failed` / `Malformed request body` / `Invalid request parameter`. Use **400 for unknown role**,
> not 422 — do not invent codes.

Schema changes / additions under `components.schemas`:

```yaml
  ClaimResponse:
    # ADD two trailing, nullable components (breaking drift, must be fixed):
    closedAt:   { type: string, format: date-time, nullable: true }
    archivedAt: { type: string, format: date-time, nullable: true }

  ClaimStatusUpdateRequest:
    # ADD, optional:
    actorId: { type: string, format: uuid, nullable: true }   # 404 when unknown

  RoleResponse:
    type: object
    required: [code, name]
    properties:
      code:        { type: string, example: CLAIMS_OFFICER }
      name:        { type: string, example: Istruttore }
      description: { type: string, nullable: true }

  ActorType:      { type: string, enum: [INTERNAL, EXTERNAL, SYSTEM] }

  ActorResponse:
    type: object
    required: [id, subjectRef, displayName, actorType, enabled, roleCodes, createdAt, updatedAt]
    properties:
      id: { type: string, format: uuid }
      subjectRef: { type: string, maxLength: 128 }
      displayName: { type: string, maxLength: 200 }
      email: { type: string, format: email, nullable: true }
      actorType: { $ref: '#/components/schemas/ActorType' }
      enabled: { type: boolean }
      roleCodes: { type: array, items: { type: string } }
      createdAt: { type: string, format: date-time }
      updatedAt: { type: string, format: date-time }

  ActorCreateRequest:
    type: object
    required: [subjectRef, displayName, actorType]
    properties:
      subjectRef:  { type: string, minLength: 3, maxLength: 128, pattern: '^[A-Za-z0-9._:@|/-]{3,128}$' }
      displayName: { type: string, maxLength: 200 }
      email:       { type: string, format: email, maxLength: 255, nullable: true }
      actorType:   { $ref: '#/components/schemas/ActorType' }
      roleCodes:   { type: array, maxItems: 10, items: { type: string }, nullable: true }

  ActorUpdateRequest:
    type: object
    properties:
      displayName: { type: string, maxLength: 200, nullable: true }
      email:       { type: string, format: email, maxLength: 255, nullable: true }
      enabled:     { type: boolean, nullable: true }

  ActorRolesUpdateRequest:
    type: object
    required: [roleCodes]
    properties:
      roleCodes: { type: array, maxItems: 10, items: { type: string } }

  ActorPageResponse:      # mirror ClaimPageResponse exactly
    type: object
    required: [content, page, size, totalElements, totalPages]
    properties:
      content: { type: array, items: { $ref: '#/components/schemas/ActorResponse' } }
      page: { type: integer }, size: { type: integer }
      totalElements: { type: integer, format: int64 }, totalPages: { type: integer }

  ClaimAssignmentRequest:
    type: object
    required: [actorId]
    properties: { actorId: { type: string, format: uuid } }

  ClaimAssignmentResponse:
    type: object
    required: [id, claimId, actorId, actorDisplayName, roleCode, assignedAt]
    properties:
      id: { type: string, format: uuid }
      claimId: { type: string, format: uuid }
      actorId: { type: string, format: uuid }
      actorDisplayName: { type: string }
      roleCode: { type: string, example: CLAIMS_OFFICER }
      assignedAt: { type: string, format: date-time }
      assignedByActorId: { type: string, format: uuid, nullable: true }
      unassignedAt: { type: string, format: date-time, nullable: true }

  AuditEventType:
    type: string
    enum: [CLAIM_OPENED, CLAIM_STATUS_CHANGED, CLAIM_DOCUMENT_LINKED, CLAIM_DOCUMENT_UNLINKED,
           CLAIM_ASSIGNED, CLAIM_UNASSIGNED, ACTOR_CREATED, ACTOR_ROLES_CHANGED]
    # exactly the 8 constants of core-app/.../domain/model/AuditEventType.java (verified)

  AuditEventResponse:
    type: object
    required: [id, eventType, entityType, entityId, outcome, occurredAt, details]
    properties:
      id: { type: string, format: uuid }
      claimId: { type: string, format: uuid, nullable: true }
      actorId: { type: string, format: uuid, nullable: true }
      eventType: { $ref: '#/components/schemas/AuditEventType' }
      entityType: { type: string, example: CLAIM }
      entityId: { type: string, format: uuid }
      outcome: { type: string, enum: [SUCCESS, FAILURE] }
      occurredAt: { type: string, format: date-time }
      requestId: { type: string, nullable: true }
      details: { type: object, additionalProperties: true }

  AuditEventPageResponse:   # same envelope as ActorPageResponse, items = AuditEventResponse
```

### 6.2 `docs/api/document-service.openapi.yaml`

```yaml
  /api/v1/documents/{documentId}/versions:
    get:        # 200 array<DocumentVersionResponse>, newest first; 404 "Document not found"
    post:       # multipart/form-data: file (required, binary), documentType (optional string)
                # 201 + Location header, DocumentResponse; 400 "Invalid document request",
                # 409 "Document version conflict" (concurrent version), 413 (MaxUploadSizeExceeded)

  /api/v1/documents/{documentId}/retention:
    patch:      # DocumentRetentionUpdateRequest → 200 DocumentResponse
                # 400 "Invalid retention policy", 409 "Document on legal hold"
```

`components.schemas.DocumentResponse` — add: `documentKey` (uuid), `versionNumber` (integer),
`isCurrent` (boolean), `keyRef` (string, nullable, *non-secret KMS alias*), `retentionUntil`
(string/date, nullable), `legalHold` (boolean). Keep `storageKey` **absent** (D9).
New: `DocumentVersionResponse` and `DocumentRetentionUpdateRequest`
(`retentionUntil: string/date nullable`, `legalHold: boolean nullable` — mirror the DTOs, do not invent).

> **Status-code truth source**: `document-service/.../adapter/in/web/GlobalExceptionHandler.java`
> → 404 `Document not found`; 400 `Invalid document request` / `Invalid retention policy`;
> 409 `Document on legal hold` / `Document version conflict`.

### 6.3 `docs/api/bff.openapi.yaml` — **no change** (statuses and claim fields consumed by the BFF are unchanged)

---

## 7. DB schema changes — DB_SKIP

**DB_SKIP: no migration may be added or edited by this task.** Verified reasons:

1. Both V2 migrations already implement every entity of the ticket (`roles`, `actors`, `actor_roles`,
   `claim_assignments`, `audit_events`, `claim_status_transition`; `documents` versioning + retention).
2. They are idempotent (`create … if not exists`, `add column if not exists`, `drop constraint if exists`
   + `add constraint`, `insert … on conflict do nothing`) and applicable **from zero** together with V1.
3. Idempotency/coherence is already machine-checked without a database by
   `SchemaMigrationContractTest` in both modules, and against a live server by
   `infra/db/verify/01-assert-schema.sql` (200 assertions, 12 tables).
4. Editing V1/V2 would break already-migrated environments (Flyway checksum).

**Optional live verification** (only if Docker is available; leave the environment clean afterwards):

```bash
cd infra && docker compose up -d postgres
# Flyway runs on service startup; or apply manually with the credentials from .env
psql "postgresql://claims:$POSTGRES_PASSWORD@localhost:5442/claims" -v ON_ERROR_STOP=1 \
     -f ../infra/db/verify/01-assert-schema.sql     # expect: SCHEMA CONTRACT OK | 200
docker compose down -v
```

---

## 8. Execution waves (order is mandatory)

| Wave | Owner | Scope | Exit gate |
|---|---|---|---|
| **W1 — Unbreak the build** | Backend | GAP-1, GAP-2, GAP-3 | `./mvnw -B -o -pl core-app test` and `-pl document-service test` → BUILD SUCCESS |
| **W2 — Second wave** | Backend | Assertion drift revealed by the 6 newly-compiled core-app test classes | 0 failures; no test deleted/`@Disabled` |
| **W3 — Format gate** | Backend | `./mvnw -B spotless:apply` (GAP-4) | `spotless:check` + `checkstyle:check` green |
| **W4 — Contracts** | Backend/Architect | GAP-5, GAP-6 → both YAML at `0.2.0` per §6 | YAML parses; every `@*Mapping` in both `adapter/in/web` packages has a path item |
| **W5 — Version** | Release | GAP-7 (VERSION + 4 POMs + 2 OAS `info.version`) | `VERSION` == root pom == `0.2.0`; full `./mvnw -B -o verify` green |
| **W6 — Commit/push** | Git agent | Conventional commit `feat(TK-64FBC8): publish claims domain contract at 0.2.0` | pushed to `main` |

**Acceptance command sequence** (copy-paste):

```bash
cd /app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa
./mvnw -B -o -pl core-app test                     # W1/W2
./mvnw -B -o -pl document-service test             # W1
./mvnw -B -o spotless:check checkstyle:check       # W3
./mvnw -B -o verify                                # W5 — full gate, all 3 modules
python3 -c "import yaml,sys;[yaml.safe_load(open(f)) for f in sys.argv[1:]]" docs/api/*.openapi.yaml
grep -H "info.version" docs/api/*.openapi.yaml     # must print 0.2.0 three times (bff may stay 0.1.0 -> align anyway)
cat VERSION; grep -m1 -A0 "<version>0.2.0</version>" pom.xml
./mvnw -B -o -pl frontend test 2>/dev/null || true # frontend NOT affected: optional sanity only
```

---

## 9. SKIP analysis

**FRONTEND_SKIP: no UI change.** The ticket alters the domain model and the DB schema only. The React
app consumes the BFF, and its status model is already complete and consistent:
`frontend/src/types/claims.ts` declares all 10 statuses (`…, CLOSED, ARCHIVED`), Italian labels and the
`ALLOWED_TRANSITIONS` map (including `CLOSED → [ARCHIVED]`, `ARCHIVED → []`). No status value, route,
page, form or component changes; `frontend/src/api/claims.ts` needs no new endpoint because the BFF
contract is untouched.

**DB_SKIP: no database change.** See §7 — both `V2` migrations already deliver the full schema of this
ticket (6 new `coreapp` tables + 13 added columns + seeded state machine + partial indexes + soft-delete
columns), they are idempotent and from-zero applicable, and both are already guarded by
`SchemaMigrationContractTest` (20 tests total) and by the 200 assertions of
`infra/db/verify/01-assert-schema.sql`. V1/V2 are immutable (Flyway checksums). No new table, column,
index, constraint or seed is required.

**BFF_SKIP: no BFF change.** `bff/src/main/java/it/enterprise/claims/bff/web/DashboardController.java`
already mirrors the 10-state lifecycle (`…, CLOSED, ARCHIVED`) and no new endpoint of the ticket is
proxied by the BFF in this phase. `docs/api/bff.openapi.yaml` stays semantically unchanged.

**NOT SKIPPED** (the actual work): `core-app` test sources, `document-service` test sources,
the two OpenAPI snapshots, and the `VERSION`/POM alignment — i.e. **test + contract + release hygiene**,
not new functionality.

---

## 10. Risks & mitigations

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Fixing GAP-1 reveals many assertion drifts in the 6 never-compiled test classes | schedule | Budget W2 explicitly; fix assertions against production behaviour, never relax them |
| R2 | Someone "helpfully" reformats or edits `V1__/V2__` migrations while fixing tests | Flyway checksum failure on every migrated env | §5.3 forbidden list; verify with `git status --short` before commit |
| R3 | OAS edited by hand with a YAML error | ADR-0009 CI gate red | Parse check in the acceptance sequence; keep indentation of neighbouring blocks |
| R4 | Version bumped in `VERSION` but not in the 4 POMs (or vice-versa) | `Version consistency` job red | Atomic procedure in GAP-7; single commit |
| R5 | Publishing `422`/invented status codes in OAS | Contract lies to consumers | §6 status tables derived from `GlobalExceptionHandler`; copy titles verbatim |
| R6 | Reducing test scope to get CI green | Violates AC-4 | No `@Disabled`, no `@Test` deletion; the DoD counts test classes in `surefire-reports` |
