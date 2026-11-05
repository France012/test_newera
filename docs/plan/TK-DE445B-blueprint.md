# TK-DE445B — Blueprint architetturale: scaffolding monorepo + pipeline CI/CD

**Stato**: approvato per implementazione
**Autore**: Architect / Planner
**Workspace root**: `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`
**Branch base**: `main` (già inizializzato da Release Manager, remote `origin` = `https://github.com/France012/test_newera.git`, nessun token → push non eseguibile in questa sessione)

---

## 0. Discovery — Current State Report

### 0.1 Struttura attuale

```
/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa
└── .git/            (repo inizializzato, 0 commit, branch main, remote origin configurato)
```

**Nessun file applicativo.** Zero commit. Zero API, zero schema DB, zero codice frontend.

### 0.2 Toolchain rilevata

| Componente                                    | Versione                                       | Note                                      |
| --------------------------------------------- | ---------------------------------------------- | ----------------------------------------- |
| OS                                            | Linux Debian 13                                |                                           |
| JDK                                           | OpenJDK **21.0.11**                            | target `--release 21`                     |
| Maven                                         | **3.9.9**                                      | `/usr/share/maven`, `~/.m2` vuoto         |
| Node                                          | **20.20.2**                                    |                                           |
| npm                                           | **10.8.2**                                     |                                           |
| Docker / docker compose                       | **ASSENTE**                                    | impossibile fare `docker build` in locale |
| trivy / cosign / syft / hadolint / actionlint | ASSENTI                                        | tool solo-CI                              |
| Rete                                          | Maven Central + npmjs raggiungibili (HTTP 200) | build locale possibili                    |

### 0.3 Conseguenze architetturali della discovery

1. **Stack scelto: Java 21 + Spring Boot 3.3.5 + Maven multi-module** (non .NET: il runtime ha già JDK 21 + Maven, .NET non è installato).
2. **Docker non c'è in locale** ⇒ i criteri "immagini prodotte e firmate" e "SBOM come artefatto" sono verificabili **solo in GitHub Actions**. QA in locale fa validazione _statica_ (esistenza Dockerfile, multi-stage, `USER` non-root, workflow con `cosign`/`sbom-action`). Questo va dichiarato esplicitamente nel report QA.
3. La build locale deve restare **verde e offline-resiliente**: test solo unitari / slice (`@WebMvcTest`, Mockito), nessun test che richieda Postgres o Docker (H2 in test scope solo se necessario, preferibilmente nessun DB nei test).
4. Le porte di default (8080/3000/5173) sono **vietate** agli agent: si standardizzano porte non standard (vedi §3.6).

---

## 1. Requisiti

### 1.1 Acceptance Criteria (dal ticket, resi misurabili)

| ID    | Criterio                                                                                | Verifica                                                                                                   |
| ----- | --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| AC-1  | Monorepo con moduli separati `core-app`, `document-service`, `bff`, `frontend`, `infra` | 5 directory di primo livello esistono e sono popolate                                                      |
| AC-2  | Build coerente Java 21 + Spring Boot con linting/formatter condivisi                    | `mvn -B verify` verde da root; `spotless:check` + `checkstyle` agganciati alla fase `verify`               |
| AC-3  | Lint/formatter anche per il frontend                                                    | `npm --prefix frontend run lint` e `npm run format:check` verdi                                            |
| AC-4  | Pipeline CI con build, unit test, lint                                                  | `.github/workflows/ci.yml` con job java + frontend                                                         |
| AC-5  | Dependency scan (SCA)                                                                   | `.github/workflows/dependency-scan.yml` (OWASP dependency-check + Trivy fs + `npm audit`)                  |
| AC-6  | SAST                                                                                    | `.github/workflows/codeql.yml` (languages: `java-kotlin`, `javascript-typescript`)                         |
| AC-7  | Secret scanning                                                                         | `.github/workflows/secret-scan.yml` (gitleaks) + `.gitleaks.toml` + `.gitignore`                           |
| AC-8  | Docker multi-stage per ogni servizio                                                    | 4 Dockerfile, stage `build` + `runtime`, `USER` non-root, `HEALTHCHECK`                                    |
| AC-9  | Firma immagini                                                                          | `.github/workflows/container-images.yml`: cosign **keyless** su `image@digest` + `attest-build-provenance` |
| AC-10 | SBOM CycloneDX come artefatto                                                           | `anchore/sbom-action` per immagine + `cyclonedx-maven-plugin` per aggregato → `actions/upload-artifact`    |
| AC-11 | `main` builda e testa ad ogni push                                                      | trigger `push: branches: [main]` + `pull_request` su ci.yml                                                |
| AC-12 | Nessun segreto in chiaro                                                                | solo `.env.example` con placeholder; grep di controllo vuoto                                               |
| AC-13 | PR template, CODEOWNERS, versioning                                                     | `.github/PULL_REQUEST_TEMPLATE.md`, `.github/CODEOWNERS`, `VERSION` + `CHANGELOG.md` + semantic-release    |

### 1.2 Definition of Done

- [ ] `mvn -B verify` da root → BUILD SUCCESS (3 moduli Java, test + spotless + checkstyle).
- [ ] `npm --prefix frontend ci && npm run lint && npm run format:check && npm run test -- --run && npm run build` → exit 0, `frontend/dist/` prodotta.
- [ ] Ogni servizio avviabile in locale: `java -jar <module>/target/*.jar --server.port=81xx` + `curl /actuator/health` → `{"status":"UP"}`.
- [ ] Tutti i workflow YAML **parsabili** (`npx --yes js-yaml .github/workflows/*.yml`).
- [ ] `grep -rniE '(ghp_|github_pat_|AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY)' . --exclude-dir=.git` → nessun match.
- [ ] `docs/api/*.yaml` = 3 contratti OpenAPI validi e coerenti con i controller.
- [ ] 5 README di modulo + README root con quickstart.
- [ ] Nessun file fuori dalla workspace root; nessun segreto, nessun artefatto binario committato.

---

## 2. Architettura

### 2.1 Vista logica

```
frontend (React 18 + TS + Vite, :5199 dev / :8188 nginx)
        │  REST /bff/v1/**  (VITE_BFF_BASE_URL)
        ▼
bff (Spring Boot, :8185) ──WebClient/RestClient──► core-app (:8180)  /api/v1/**
        └───────────────────────────────────────► document-service (:8181) /api/v1/documents/**
                                                        │
                        PostgreSQL 16 (:5442)  ◄─────────┴────► schema coreapp / documents
```

### 2.2 Decisioni di stack (vincolanti per gli sviluppatori)

| Ambito            | Decisione                                                                                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Linguaggio BE     | Java 21, Spring Boot **3.3.5**, `spring-boot-starter-parent` come parent del root POM                                                                                                |
| Build BE          | Maven multi-module, root `pom.xml` `<packaging>pom</packaging>` con `<modules>`                                                                                                      |
| Versioning Maven  | versione letterale `0.1.0` nel root POM, propagata ai figli; release via `mvn versions:set -DnewVersion=$(cat VERSION)` (evita `flatten-maven-plugin`, meno rischio) — vedi ADR-0004 |
| Formatter BE      | **Spotless** + `google-java-format` (AOSP? no: `google-java-format` 1.22.0), `spotless:check` in `verify`                                                                            |
| Lint BE           | **Checkstyle** 10.18.x (`config/checkstyle/checkstyle.xml`), `failOnViolation=true`, `violationSeverity=warning`                                                                     |
| Bug/security BE   | SpotBugs 4.8.x + `findsecbugs` (opzionale ma previsto), `effort=Max`, `threshold=High`                                                                                               |
| Copertura         | JaCoCo 0.8.12, report (no gate bloccante in fase 1)                                                                                                                                  |
| API docs          | **springdoc-openapi 2.6.0** (`/swagger-ui.html`, `/v3/api-docs`) + contratti versionati in `docs/api/`                                                                               |
| Persistenza       | Spring Data JPA + PostgreSQL 16, migrazioni **Flyway** (`spring.flyway.enabled=true`), un DB per servizio (database-per-service)                                                     |
| Frontend          | React 18 + TypeScript 5.5 + Vite 5, React Router 6, **Vitest** + Testing Library, ESLint 9 flat config + Prettier 3                                                                  |
| HTTP client FE    | `fetch` wrapper custom (`src/api/client.ts`) — niente axios                                                                                                                          |
| CI/CD             | **GitHub Actions** (repo è GitHub) — non GitLab CI                                                                                                                                   |
| Registry immagini | GHCR `ghcr.io/france012/test_newera/<modulo>`                                                                                                                                        |
| Firma             | **cosign keyless** (OIDC GitHub) — nessuna chiave privata nel repo                                                                                                                   |
| SBOM              | **CycloneDX** via `anchore/sbom-action` (per immagine) + `cyclonedx-maven-plugin` (aggregato Maven)                                                                                  |
| Versioning app    | SemVer 2.0 + Conventional Commits + semantic-release (`CHANGELOG.md` auto)                                                                                                           |
| Sicurezza runtime | **fuori scope fase 1**: in `bff` c'è `spring-boot-starter-security` con `SecurityFilterChain` placeholder `permitAll` + TODO esplicito (ADR-0005)                                    |

### 2.3 API contract — `core-app` (`:8180`, base `/api/v1`)

| Method | Path                                 | Request                                                                               | Response                                                                                 | Note                    |
| ------ | ------------------------------------ | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- | ----------------------- |
| GET    | `/api/v1/claims`                     | query: `status?`, `page=0`, `size=20`, `sort=createdAt,desc`                          | `200 ClaimPageResponse {content:[ClaimResponse], page, size, totalElements, totalPages}` | lista pratiche          |
| GET    | `/api/v1/claims/{id}`                | —                                                                                     | `200 ClaimResponse` / `404 ProblemDetail`                                                | dettaglio               |
| POST   | `/api/v1/claims`                     | `ClaimCreateRequest {policyNumber, customerId, claimType, description, incidentDate}` | `201 ClaimResponse` + `Location`                                                         | apertura pratica        |
| PATCH  | `/api/v1/claims/{id}/status`         | `ClaimStatusUpdateRequest {status, note?}`                                            | `200 ClaimResponse` / `409` transizione illegale                                         | macchina a stati        |
| GET    | `/api/v1/claims/{id}/status-history` | —                                                                                     | `200 [ClaimStatusHistoryResponse]`                                                       | audit                   |
| POST   | `/api/v1/claims/{id}/documents`      | `{documentIds:[uuid]}`                                                                | `200 ClaimResponse`                                                                      | link a document-service |
| GET    | `/api/v1/policies`                   | `page/size`                                                                           | `200 PolicyPageResponse`                                                                 | polizze                 |
| GET    | `/api/v1/policies/{id}`              | —                                                                                     | `200 PolicyResponse` / `404`                                                             |                         |
| GET    | `/actuator/health`                   | —                                                                                     | `200 {status:UP}`                                                                        | liveness (no auth)      |

`ClaimResponse` = `{id, claimNumber, policyNumber, customerId, claimType, status, description, incidentDate, openedAt, updatedAt, documentIds[]}`
`ClaimStatus` enum = `DRAFT, SUBMITTED, UNDER_REVIEW, INTEGRATION_REQUESTED, APPROVED, REJECTED, CLOSED`
Transizioni ammesse: `DRAFT→SUBMITTED`, `SUBMITTED→UNDER_REVIEW`, `UNDER_REVIEW→INTEGRATION_REQUESTED|APPROVED|REJECTED`, `INTEGRATION_REQUESTED→UNDER_REVIEW`, `APPROVED|REJECTED→CLOSED`. Ogni altra → `409 Conflict` con `ProblemDetail`.

Errori: RFC 9457 `application/problem+json` via `@RestControllerAdvice` (`GlobalExceptionHandler`).

### 2.4 API contract — `document-service` (`:8181`, base `/api/v1/documents`)

| Method | Path                             | Request                                                  | Response                                                         |
| ------ | -------------------------------- | -------------------------------------------------------- | ---------------------------------------------------------------- |
| POST   | `/api/v1/documents`              | `multipart/form-data`: `file`, `claimId`, `documentType` | `201 DocumentResponse` + `Location`                              |
| GET    | `/api/v1/documents/{id}`         | —                                                        | `200 DocumentResponse` / `404`                                   |
| GET    | `/api/v1/documents/{id}/content` | —                                                        | `200` stream (`application/octet-stream`, `Content-Disposition`) |
| GET    | `/api/v1/documents?claimId=`     | query `claimId`                                          | `200 DocumentPageResponse`                                       |
| DELETE | `/api/v1/documents/{id}`         | —                                                        | `204` / `404`                                                    |
| GET    | `/actuator/health`               | —                                                        | `200 {status:UP}`                                                |

`DocumentResponse = {id, claimId, fileName, contentType, sizeBytes, documentType, checksumSha256, uploadedAt, version}`
Limiti: `spring.servlet.multipart.max-file-size=20MB`. Storage astratto da porta `DocumentStoragePort` con implementazione `LocalFileSystemStorage` (root da env `DOCUMENTS_STORAGE_PATH`, default `./target/storage`), pronta per adapter S3.

### 2.5 API contract — `bff` (`:8185`, base `/bff/v1`)

| Method  | Path                        | Aggrega                                                          | Response                                           |
| ------- | --------------------------- | ---------------------------------------------------------------- | -------------------------------------------------- |
| GET     | `/bff/v1/claims`            | core-app `/claims`                                               | `ClaimSummaryResponse[]` (senza documenti)         |
| GET     | `/bff/v1/claims/{id}`       | core-app `/claims/{id}` + document-service `/documents?claimId=` | `ClaimDetailResponse {claim, documents[]}`         |
| GET     | `/bff/v1/dashboard/summary` | core-app `/claims` (conteggi per status)                         | `DashboardSummaryResponse {totalOpen, byStatus{}}` |
| GET     | `/actuator/health`          | health aggregato                                                 | `200`                                              |
| OPTIONS | any                         | CORS preflight                                                   | `204`                                              |

CORS: origini da env `BFF_ALLOWED_ORIGINS` (default `http://localhost:5199`), mai `*` con credenziali.
Downstream URL da env: `CORE_APP_BASE_URL`, `DOCUMENT_SERVICE_BASE_URL` (default localhost:8180/8181). Nessun URL hardcoded con host di produzione.

### 2.6 DB schema (Flyway)

`core-app/src/main/resources/db/migration/V1__init_claims.sql`

```sql
create table customers (id uuid primary key, fiscal_code varchar(16) not null unique, full_name varchar(200) not null, email varchar(255), created_at timestamptz not null default now());
create table policies (id uuid primary key, policy_number varchar(32) not null unique, customer_id uuid not null references customers(id), product_code varchar(32) not null, valid_from date not null, valid_to date not null, premium numeric(12,2) not null, created_at timestamptz not null default now());
create table claims (id uuid primary key, claim_number varchar(32) not null unique, policy_id uuid not null references policies(id), claim_type varchar(32) not null, status varchar(32) not null, description varchar(2000), incident_date date not null, opened_at timestamptz not null default now(), updated_at timestamptz not null default now(), version bigint not null default 0);
create index idx_claims_status on claims(status);
create index idx_claims_policy on claims(policy_id);
create table claim_status_history (id uuid primary key, claim_id uuid not null references claims(id) on delete cascade, from_status varchar(32), to_status varchar(32) not null, note varchar(500), changed_at timestamptz not null default now());
create table claim_documents (claim_id uuid not null references claims(id) on delete cascade, document_id uuid not null, linked_at timestamptz not null default now(), primary key (claim_id, document_id));
```

`document-service/src/main/resources/db/migration/V1__init_documents.sql`

```sql
create table documents (id uuid primary key, claim_id uuid not null, file_name varchar(255) not null, content_type varchar(128) not null, size_bytes bigint not null, document_type varchar(32) not null, checksum_sha256 char(64) not null, storage_key varchar(512) not null, version int not null default 1, uploaded_at timestamptz not null default now(), deleted_at timestamptz);
create index idx_documents_claim on documents(claim_id);
```

Vincoli: `claim_id` in document-service è **riferimento logico** (nessuna FK cross-service). UUID generati in application layer.

### 2.7 Struttura package Java (convenzione unica)

`it.enterprise.claims.<module>`

- `<module>/domain/model`, `domain/port`
- `<module>/application` + `application/dto`
- `<module>/adapter/in/web`, `adapter/out/persistence` (hexagonal-lite)
- `<module>/config`

Moduli: `core` (`it.enterprise.claims.core`), `documents` (`it.enterprise.claims.documents`), `bff` (`it.enterprise.claims.bff`).

### 2.8 Frontend

Route: `/` → `DashboardPage`, `/claims` → `ClaimsListPage`, `/claims/:id` → `ClaimDetailPage`.
Componenti: `Layout`, `ClaimsTable`, `ClaimStatusBadge`, `DocumentUploader`, `EmptyState`, `ErrorBanner`.
Tipi in `src/types/claims.ts` allineati a `docs/api/bff.openapi.yaml`. Client: `src/api/client.ts` (baseURL da `import.meta.env.VITE_BFF_BASE_URL`, timeout, error normalization) + `src/api/claims.ts`.
Test: `client.test.ts` (mock fetch), `ClaimStatusBadge.test.tsx`, `ClaimsListPage.test.tsx` (mock api).

### 2.9 Porte standardizzate (VIETATO 8000/8080/3000/5173)

| Servizio                   | Porta                    |
| -------------------------- | ------------------------ |
| core-app                   | **8180**                 |
| document-service           | **8181**                 |
| bff                        | **8185**                 |
| frontend (nginx container) | **8188**                 |
| vite dev server            | **5199** (`--port 5199`) |
| PostgreSQL host            | **5442**                 |

### 2.10 CI/CD — workflow e responsabilità

| File                                     | Trigger                 | Contenuto                                                                                                                         |
| ---------------------------------------- | ----------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `.github/workflows/ci.yml`               | push/PR su `main`       | job `java-verify` (`./mvnw -B verify`), `frontend-ci` (npm ci/lint/format/test/build + artefatto `dist`), `aggregate`             |
| `.github/workflows/dependency-scan.yml`  | push/PR + schedule cron | OWASP dependency-check, `npm audit --audit-level=high`, Trivy fs → SARIF → code scanning                                          |
| `.github/workflows/codeql.yml`           | push/PR/schedule        | CodeQL `java-kotlin` (manual build) + `javascript-typescript`                                                                     |
| `.github/workflows/secret-scan.yml`      | push/PR/schedule        | `gitleaks/gitleaks-action@v2` con `.gitleaks.toml`, full history                                                                  |
| `.github/workflows/container-images.yml` | push `main` + tag `v*`  | buildx 4 immagini → GHCR, `sbom-action` CycloneDX, `cosign sign --yes` su digest, `attest-build-provenance`, upload SBOM artifact |
| `.github/workflows/release.yml`          | push `main`             | semantic-release (tag + CHANGELOG + GitHub Release)                                                                               |
| `.github/workflows/pr-title.yml`         | PR                      | `amannn/action-semantic-pull-request` (Conventional Commits)                                                                      |

Permessi minimi per job; `container-images.yml` richiede `contents: read, packages: write, id-token: write, attestations: write`.
Secret/variabili richiesti (documentati in `docs/ci.md`): `NVD_API_KEY` (opzionale, SCA più veloce); GHCR usa `GITHUB_TOKEN`; **nessun altro segreto**.

### 2.11 Formatter/lint condivisi (root)

`config/checkstyle/checkstyle.xml`, `config/checkstyle/suppressions.xml`, `config/spotbugs/exclude.xml`, root `.editorconfig`, root `.prettierrc.json` + `.prettierignore` (ereditati dal frontend).

---

## 3. Piano di implementazione — manifest file ESATTI

> Tutti i path sono relativi a `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`.

### Wave A — Fondamenta (BLOCCANTE, fare per primo, un solo agente)

| #   | Path                                                                           | Contenuto chiave                                                                                                                                                                                                                                                                                                                                    |
| --- | ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A1  | `pom.xml`                                                                      | parent `spring-boot-starter-parent:3.3.5`, `packaging=pom`, `<modules>` 3, `<version>0.1.0</version>`, `<java.version>21</java.version>`, dependencyManagement (springdoc 2.6.0), pluginManagement (compiler/surefire/jacoco 0.8.12/spotless 2.43/checkstyle 3.6/spotbugs 4.8.6/cyclonedx 2.8.0), proprietà spotless+checkstyle puntate a `config/` |
| A2  | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`                    | generati con `mvn -N -B wrapper:wrapper -Dtype=only-script -Dmaven=3.9.9`                                                                                                                                                                                                                                                                           |
| A3  | `.gitignore`                                                                   | target/, node_modules/, dist/, .env, *.log, .idea/, .vscode/, sbom/, *.spdx.json, coverage/                                                                                                                                                                                                                                                         |
| A4  | `.gitattributes`                                                               | `* text=auto eol=lf`, `mvnw text eol=lf`, `*.jar binary`                                                                                                                                                                                                                                                                                            |
| A5  | `.editorconfig`                                                                | utf-8, lf, indent 4 (java/xml), 2 (ts/tsx/json/yml), final newline                                                                                                                                                                                                                                                                                  |
| A6  | `.prettierrc.json` / `.prettierignore`                                         | printWidth 100, singleQuote, semi, trailingComma all                                                                                                                                                                                                                                                                                                |
| A7  | `.env.example`                                                                 | `POSTGRES_USER=claims`, `POSTGRES_PASSWORD=__change_me__`, `CORE_APP_BASE_URL=...`, `BFF_ALLOWED_ORIGINS=...`, `VITE_BFF_BASE_URL=...`, `DOCUMENTS_STORAGE_PATH=...` — **solo placeholder**                                                                                                                                                         |
| A8  | `.gitleaks.toml`                                                               | extend default rules, allowlist per `.env.example` e test fixtures                                                                                                                                                                                                                                                                                  |
| A9  | `.releaserc.json`                                                              | branche `main`, plugin commit-analyzer/release-notes-generator/changelog/npm(no publish)/git                                                                                                                                                                                                                                                        |
| A10 | `VERSION`                                                                      | `0.1.0`                                                                                                                                                                                                                                                                                                                                             |
| A11 | `CHANGELOG.md`                                                                 | Keep a Changelog, sezione `[Unreleased]`, `[0.1.0] - scaffolding`                                                                                                                                                                                                                                                                                   |
| A12 | `README.md`                                                                    | badge, architettura (mermaid), quickstart, porte, comandi lint/test/build, link a docs                                                                                                                                                                                                                                                              |
| A13 | `CONTRIBUTING.md`                                                              | Conventional Commits, branch `feat                                                                                                                                                                                                                                                                                                                  | fix | chore/TK-xxx-slug`, PR flow, DoD locale |
| A14 | `SECURITY.md`                                                                  | come riportare vulnerabilità, gestione segreti (env/OIDC, no hardcode), policy SCA                                                                                                                                                                                                                                                                  |
| A15 | `config/checkstyle/checkstyle.xml`                                             | sun_checks base + regole naming/import/javadoc-lite, severity warning                                                                                                                                                                                                                                                                               |
| A16 | `config/checkstyle/suppressions.xml`                                           | esclude dto/generated                                                                                                                                                                                                                                                                                                                               |
| A17 | `config/spotbugs/exclude.xml`                                                  | esclude dto/config                                                                                                                                                                                                                                                                                                                                  |
| A18 | `.github/CODEOWNERS`                                                           | `* @France012` + righe per `/core-app/`, `/document-service/`, `/bff/`, `/frontend/`, `/infra/`, `/.github/`                                                                                                                                                                                                                                        |
| A19 | `.github/PULL_REQUEST_TEMPLATE.md`                                             | ticket, tipo, checklist AC, impatto DB/API, evidenze test                                                                                                                                                                                                                                                                                           |
| A20 | `.github/ISSUE_TEMPLATE/bug_report.yml` + `feature_request.yml` + `config.yml` | form-based                                                                                                                                                                                                                                                                                                                                          |
| A21 | `.github/dependabot.yml`                                                       | maven, npm (`/frontend`), github-actions, docker — weekly                                                                                                                                                                                                                                                                                           |
| A22 | `docs/api/core-app.openapi.yaml`                                               | OpenAPI 3.0.3, tutte le rotte §2.3, schemi + esempi                                                                                                                                                                                                                                                                                                 |
| A23 | `docs/api/document-service.openapi.yaml`                                       | §2.4 (multipart incluso)                                                                                                                                                                                                                                                                                                                            |
| A24 | `docs/api/bff.openapi.yaml`                                                    | §2.5                                                                                                                                                                                                                                                                                                                                                |
| A25 | `docs/architecture/README.md`                                                  | vista C4-lite mermaid + tabella moduli/responsabilità                                                                                                                                                                                                                                                                                               |
| A26 | `docs/architecture/adr/0001-monorepo-maven-multi-module.md`                    | contesto/decisione/conseguenze                                                                                                                                                                                                                                                                                                                      |
| A27 | `docs/architecture/adr/0002-github-actions-ci.md`                              | perché GH Actions, job, gate                                                                                                                                                                                                                                                                                                                        |
| A28 | `docs/architecture/adr/0003-cosign-keyless-signing.md`                         | keyless OIDC, nessuna chiave nel repo, prerequisiti                                                                                                                                                                                                                                                                                                 |
| A29 | `docs/architecture/adr/0004-versioning-semver.md`                              | VERSION + versions:set vs revision/flatten                                                                                                                                                                                                                                                                                                          |
| A30 | `docs/architecture/adr/0005-security-baseline-deferred.md`                     | auth/OIDC rinviata, placeholder in bff                                                                                                                                                                                                                                                                                                              |
| A31 | `docs/versioning.md`                                                           | SemVer, conventional commits, flow release                                                                                                                                                                                                                                                                                                          |
| A32 | `docs/ci.md`                                                                   | mappa workflow, secret richiesti, come riprodurre in locale, limiti (no docker locale)                                                                                                                                                                                                                                                              |

### Wave B — `core-app`

| #   | Path                                                                                                                                                                                                                                                              |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B1  | `core-app/pom.xml` (deps: web, validation, data-jpa, actuator, flyway, postgresql runtime, springdoc, test)                                                                                                                                                       |
| B2  | `core-app/README.md`                                                                                                                                                                                                                                              |
| B3  | `core-app/src/main/java/it/enterprise/claims/core/CoreAppApplication.java`                                                                                                                                                                                        |
| B4  | `.../core/domain/model/Claim.java`, `ClaimStatus.java`, `ClaimStatusHistory.java`, `Policy.java`, `Customer.java`                                                                                                                                                 |
| B5  | `.../core/domain/port/ClaimRepositoryPort.java`, `PolicyRepositoryPort.java`, `ClaimDocumentPort.java`                                                                                                                                                            |
| B6  | `.../core/application/ClaimService.java`, `PolicyService.java`                                                                                                                                                                                                    |
| B7  | `.../core/application/dto/ClaimCreateRequest.java`, `ClaimStatusUpdateRequest.java`, `ClaimResponse.java`, `ClaimPageResponse.java`, `ClaimStatusHistoryResponse.java`, `DocumentIdsRequest.java`, `PolicyResponse.java`, `PolicyPageResponse.java`               |
| B8  | `.../core/adapter/in/web/ClaimController.java`, `PolicyController.java`, `GlobalExceptionHandler.java`                                                                                                                                                            |
| B9  | `.../core/adapter/out/persistence/ClaimJpaEntity.java`, `ClaimStatusHistoryJpaEntity.java`, `ClaimDocumentJpaEntity.java`, `PolicyJpaEntity.java`, `CustomerJpaEntity.java`, `*JpaRepository.java`, `ClaimRepositoryAdapter.java`, `PolicyRepositoryAdapter.java` |
| B10 | `.../core/config/OpenApiConfig.java`                                                                                                                                                                                                                              |
| B11 | `core-app/src/main/resources/application.yml` (+ `application-local.yml`, `application-test.yml`) — `server.port: 8180`, datasource da env, flyway, actuator health                                                                                               |
| B12 | `core-app/src/main/resources/db/migration/V1__init_claims.sql` (§2.6)                                                                                                                                                                                             |
| B13 | `core-app/src/test/java/.../ClaimServiceTest.java` (Mockito, transizioni valide/invalide)                                                                                                                                                                         |
| B14 | `core-app/src/test/java/.../ClaimControllerTest.java` (`@WebMvcTest` + `@MockBean`, casi 200/201/404/409)                                                                                                                                                         |

### Wave C — `document-service` e `bff` (paralleli)

| #   | Path                                                                                                                           |
| --- | ------------------------------------------------------------------------------------------------------------------------------ |
| C1  | `document-service/pom.xml`, `document-service/README.md`                                                                       |
| C2  | `.../documents/DocumentServiceApplication.java`                                                                                |
| C3  | `.../documents/domain/model/Document.java`, `.../domain/port/DocumentRepositoryPort.java`, `DocumentStoragePort.java`          |
| C4  | `.../documents/application/DocumentService.java`, `.../application/dto/DocumentResponse.java`, `DocumentPageResponse.java`     |
| C5  | `.../documents/adapter/in/web/DocumentController.java`, `GlobalExceptionHandler.java`                                          |
| C6  | `.../documents/adapter/out/persistence/DocumentJpaEntity.java`, `DocumentJpaRepository.java`, `DocumentRepositoryAdapter.java` |
| C7  | `.../documents/adapter/out/storage/LocalFileSystemStorage.java`                                                                |
| C8  | `.../documents/config/OpenApiConfig.java`                                                                                      |
| C9  | `document-service/src/main/resources/application.yml` (port 8181, multipart 20MB, `DOCUMENTS_STORAGE_PATH`)                    |
| C10 | `document-service/src/main/resources/db/migration/V1__init_documents.sql`                                                      |
| C11 | test: `DocumentServiceTest.java`, `DocumentControllerTest.java` (multipart MockMvc)                                            |
| C12 | `bff/pom.xml` (web, security, actuator, springdoc), `bff/README.md`                                                            |
| C13 | `.../bff/BffApplication.java`                                                                                                  |
| C14 | `.../bff/config/RestClientConfig.java`, `CorsConfig.java`, `SecurityConfig.java` (permitAll + TODO), `OpenApiConfig.java`      |
| C15 | `.../bff/client/ClaimsClient.java`, `DocumentsClient.java`                                                                     |
| C16 | `.../bff/web/BffClaimController.java`, `DashboardController.java`                                                              |
| C17 | `.../bff/web/dto/ClaimDetailResponse.java`, `ClaimSummaryResponse.java`, `DashboardSummaryResponse.java`                       |
| C18 | `bff/src/main/resources/application.yml` (port 8185, URL downstream da env, CORS)                                              |
| C19 | test: `BffClaimControllerTest.java` (`@WebMvcTest` + `@MockBean` client), `DashboardControllerTest.java`                       |

### Wave D — `frontend`

| #   | Path                                                                                                                                                                                 |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| D1  | `frontend/package.json` (react 18, react-router-dom 6, vite 5, typescript 5.5, vitest 2, @testing-library/*, eslint 9 + typescript-eslint + react-hooks + react-refresh, prettier 3) |
| D2  | `frontend/README.md`, `frontend/.env.example` (`VITE_BFF_BASE_URL=http://localhost:8185`)                                                                                            |
| D3  | `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/vite.config.ts` (server.port 5199, proxy `/bff` → 8185, test env jsdom)                                           |
| D4  | `frontend/index.html`                                                                                                                                                                |
| D5  | `frontend/eslint.config.js` (flat config)                                                                                                                                            |
| D6  | `frontend/src/main.tsx`, `App.tsx`, `router.tsx`                                                                                                                                     |
| D7  | `frontend/src/api/client.ts`, `frontend/src/api/claims.ts`                                                                                                                           |
| D8  | `frontend/src/types/claims.ts`                                                                                                                                                       |
| D9  | `frontend/src/pages/DashboardPage.tsx`, `ClaimsListPage.tsx`, `ClaimDetailPage.tsx`                                                                                                  |
| D10 | `frontend/src/components/Layout.tsx`, `ClaimsTable.tsx`, `ClaimStatusBadge.tsx`, `DocumentUploader.tsx`, `EmptyState.tsx`, `ErrorBanner.tsx`                                         |
| D11 | `frontend/src/styles/global.css`                                                                                                                                                     |
| D12 | `frontend/src/test/setup.ts`, `frontend/src/api/client.test.ts`, `frontend/src/components/ClaimStatusBadge.test.tsx`, `frontend/src/pages/ClaimsListPage.test.tsx`                   |
| D13 | `frontend/Dockerfile`? **NO** → il Dockerfile frontend sta in `infra/docker/frontend.Dockerfile` (§E)                                                                                |

### Wave E — `infra` + workflow CI

| #   | Path                                                                                                                         | Contenuto                                                                                                                                                                                                                                            |
| --- | ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| E1  | `infra/README.md`                                                                                                            | mappa infra, comandi, prerequisiti                                                                                                                                                                                                                   |
| E2  | `infra/docker/core-app.Dockerfile`                                                                                           | stage `build` (maven:3.9-eclipse-temurin-21 → `mvn -B -pl core-app -am package -DskipTests`), stage `runtime` (eclipse-temurin:21-jre-alpine), `USER 10001:10001`, `HEALTHCHECK curl actuator/health`, `EXPOSE 8180`, `ENTRYPOINT java -jar app.jar` |
| E3  | `infra/docker/document-service.Dockerfile`                                                                                   | idem, `EXPOSE 8181`, volume `/var/lib/documents`                                                                                                                                                                                                     |
| E4  | `infra/docker/bff.Dockerfile`                                                                                                | idem, `EXPOSE 8185`                                                                                                                                                                                                                                  |
| E5  | `infra/docker/frontend.Dockerfile`                                                                                           | stage `build` node:20-alpine (`npm ci && npm run build`), stage `runtime` nginx:1.27-alpine, `USER nginx`, `EXPOSE 8188`                                                                                                                             |
| E6  | `infra/docker/nginx/default.conf`                                                                                            | `listen 8188`, SPA fallback, `/bff` proxy → `bff:8185`, security headers                                                                                                                                                                             |
| E7  | `infra/docker-compose.yml`                                                                                                   | postgres:16-alpine (host 5442), core-app, document-service, bff, frontend, healthchecks, env da `.env` (variabili, mai valori)                                                                                                                       |
| E8  | `infra/scripts/build-images.sh`                                                                                              | build 4 immagini con tag `$VERSION` + `$GIT_SHA`                                                                                                                                                                                                     |
| E9  | `infra/scripts/generate-sbom.sh`                                                                                             | cyclonedx aggregato + syft per immagine                                                                                                                                                                                                              |
| E10 | `infra/scripts/sign-images.sh`                                                                                               | `cosign sign --yes $IMAGE@$DIGEST`                                                                                                                                                                                                                   |
| E11 | `infra/k8s/base/namespace.yaml`, `core-app.yaml`, `document-service.yaml`, `bff.yaml`, `frontend.yaml`, `kustomization.yaml` | Deployment+Service+probes, image digest placeholder, resources                                                                                                                                                                                       |
| E12 | `.github/workflows/ci.yml`                                                                                                   | §2.10                                                                                                                                                                                                                                                |
| E13 | `.github/workflows/dependency-scan.yml`                                                                                      | §2.10                                                                                                                                                                                                                                                |
| E14 | `.github/workflows/codeql.yml`                                                                                               | §2.10                                                                                                                                                                                                                                                |
| E15 | `.github/workflows/secret-scan.yml`                                                                                          | §2.10                                                                                                                                                                                                                                                |
| E16 | `.github/workflows/container-images.yml`                                                                                     | §2.10                                                                                                                                                                                                                                                |
| E17 | `.github/workflows/release.yml`                                                                                              | §2.10                                                                                                                                                                                                                                                |
| E18 | `.github/workflows/pr-title.yml`                                                                                             | §2.10                                                                                                                                                                                                                                                |

### Wave F — QA / verifica

| #   | Azione                                                                                                                                                                               |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| F1  | `mvn -B verify` (root) → BUILD SUCCESS                                                                                                                                               |
| F2  | `npm --prefix frontend ci && npm --prefix frontend run lint && npm --prefix frontend run format:check && npm --prefix frontend run test -- --run && npm --prefix frontend run build` |
| F3  | Smoke avvio servizi su porte 8180/8181/8185 + `curl /actuator/health`                                                                                                                |
| F4  | `npx --yes js-yaml <workflow>.yml` per ogni workflow                                                                                                                                 |
| F5  | grep anti-segreto (§1.2)                                                                                                                                                             |
| F6  | Report esplicito: docker/cosign/SBOM **non eseguibili in locale** → validazione statica + CI come fonte di verità                                                                    |

---

## 4. Ordine di esecuzione e parallelizzazione

1. **Wave A** obbligatoria e sequenziale (tutti gli altri agenti dipendono da `pom.xml`, `config/`, `docs/api/`).
2. **Wave B** poi **C** (o C1–C11 e C12–C19 in parallelo), **D** in parallelo a C (dipende solo da `docs/api/bff.openapi.yaml`).
3. **Wave E** dopo che i path `core-app/`, `document-service/`, `bff/`, `frontend/` esistono (i Dockerfile referenziano `-pl <module>`).
4. **Wave F** alla fine.

## 5. Rischi e mitigazioni

| Rischio                                             | Mitigazione                                                                                             |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Docker assente in locale → Dockerfile non testabili | multi-stage minimali, no build-arg segreti, validazione statica + `docker buildx` verificato solo in CI |
| `mvnw` richiede download wrapper                    | se `wrapper:wrapper` fallisce, si usa `mvn` e si documenta; CI usa `mvn -B` come fallback               |
| Risoluzione dipendenze in CI                        | `setup-java` con `cache: maven`, `setup-node` con `cache: npm`                                          |
| Test che richiedono DB → build rossa                | solo unit/slice test, nessun `@SpringBootTest` con datasource reale                                     |
| Cosign keyless su repo privato/fork                 | documentare in ADR-0003: richiede `id-token: write`; per fork usare chiave in secret (fuori scope)      |
| Segreti nel repo                                    | `.env.example` con placeholder, gitleaks pre-commit consigliato, `.gitignore` su `.env`                 |
| Drift contratti OpenAPI vs codice                   | springdoc runtime + snapshot in `docs/api/`, review in PR template                                      |

## 6. Domini NON impattati

- **FRONTEND_SKIP: NO** — `frontend/` è in scope, ma **solo scaffolding**: 3 pagine placeholder + client API + test minimi. Nessuna UI di business completa, nessun design system, nessuna i18n, nessun E2E Playwright in questa fase.
- **DB_SKIP: NO** — 2 schemi Flyway (`coreapp`, `documents`) con tabelle §2.6. **Nessuna migrazione dati**, nessun seed di produzione, nessun DB reale nei test.
- **BACKEND_SKIP: NO** — 3 servizi Spring Boot con API §2.3–2.5. **Fuori scope**: autenticazione/autorizzazione OIDC reale, integrazione S3 reale, messaggistica, osservabilità avanzata (solo actuator health).
