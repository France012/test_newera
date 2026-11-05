# TK-DE445B — Piano di delivery: Wave A-remainder (governance) + Wave E (infra, container, CI/CD)

**Stato**: approvato per implementazione — vincolante per gli agenti di sviluppo
**Autore**: Architect / Planner
**Data**: iterazione successiva a `2dc711a`
**Workspace root**: `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`
**Documento base**: `docs/plan/TK-DE445B-blueprint.md` (Wave 0–F, §2 architettura/API/DB/FE) — **resta la fonte di verità per tutto ciò che è già implementato**. Questo piano **non lo sostituisce**: copre esclusivamente le parti di Wave A e Wave E che il workspace ancora non contiene, più le decisioni che la realtà del repo ha reso necessarie.

---

## Sintesi per il PO (leggere solo questo, se si ha fretta)

Le Wave **B (core-app)**, **C (document-service + bff)** e **D (frontend)** sono **complete e presenti** nel repo: 139 file tracciati, worktree pulito al commit `2dc711a`.

**Mancano completamente**: `.github/**` (quindi **nessuna CI**: AC-4, AC-5, AC-6, AC-7, AC-9, AC-10, AC-11, AC-13 non soddisfatti), `infra/docker/**` (AC-8), i documenti di governance root (`README`, `CONTRIBUTING`, `SECURITY`, `VERSION`, `CHANGELOG`, `.editorconfig`, `.gitattributes`, `.env.example`, `.gitleaks.toml`, `.releaserc.json`).

**Conseguenza**: i criteri "immagini prodotte e firmate in CI" e "SBOM come artefatto" **non sono oggi realizzabili in alcun modo**, perché non esiste alcuna pipeline che li produca. Questo piano li rende eseguibili in 2 wave (A-rem → E) senza toccare nemmeno una riga di codice applicativo Java/TS.

**Blocco organizzativo noto, fuori dal perimetro tecnico**: il token Git fornito ha sola lettura → il push su `origin` risponde `403`. Finché il token non ottiene `contents: write` su `France012/test_newera`, la pipeline **non può girare** (AC-11 "main builda ad ogni push" resta non verificabile end-to-end). Il codice/CI è comunque completo e corretto: appena i permessi ci sono, il primo push su `main` esegue tutto.

---

## 0. Current State Report (verificato in questa sessione)

Comandi usati: `find` su directory note, `git ls-files`, `git status --porcelain`, `command -v`, lettura diretta dei file citati. **Nessuna scoperta basata su assunzioni.**

### 0.1 Presente e verificato

| Area             | Contenuto verificato                                                                                                                                                                                                                                                                                                                                                   |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Root build       | `pom.xml` (aggregatore `claims-platform:0.1.0`, `packaging=pom`, parent `spring-boot-starter-parent:3.3.5`, `<modules>` core-app/document-service/bff, `java.version=21`, spotless 2.43.0, checkstyle 3.6.0, jacoco 0.8.12, cyclonedx 2.8.0 in `pluginManagement`, `checkstyle.config.location=${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml`) |
| Wrapper          | `mvnw` (mode git **100755**, ok), `mvnw.cmd` (100644, ok), `.mvn/wrapper/maven-wrapper.properties` (`distributionType=only-script`, Maven **3.9.9**), `.mvn/jvm.config` (7 `--add-exports` per google-java-format)                                                                                                                                                     |
| Igiene           | `.gitignore` (con `target/`, `/out/` ancorato, `.env`/`.env.*` con eccezione `!.env.example`, `node_modules/`, `dist/`, `sbom/`, `*.sarif`)                                                                                                                                                                                                                            |
| Lint/format BE   | `config/checkstyle/checkstyle.xml` (severity `warning`, `LineLength` via `${checkstyle.max.line.length}=120`, naming, import discipline)                                                                                                                                                                                                                               |
| core-app         | 48 file: `CoreAppApplication`, 5 model di dominio, 3 port, 2 service, 8 DTO, `ClaimController` (5 endpoint), `PolicyController` (2), `GlobalExceptionHandler` (10 handler → RFC 9457), `PageRequests`, 9 classi di persistenza, `OpenApiConfig`, `ClockConfig`, `application.yml` (+`application-local.yml`), `V1__init_claims.sql`, 2 test                            |
| document-service | `DocumentController` (5 endpoint), `DocumentService`, `LocalFileSystemStorage`, 3 port/model, 3 eccezioni, `V1__init_documents.sql`, 2 test                                                                                                                                                                                                                            |
| bff              | `BffApplication`, 4 config (`RestClientConfig`, `CorsConfig`, `SecurityConfig` permitAll+TODO, `OpenApiConfig`), 2 client + 2 payload, `BffClaimController` (2), `DashboardController` (1), 4 DTO, `GlobalExceptionHandler` (404/502/503), `application.yml`, 2 test                                                                                                   |
| frontend         | React 18.3 + TS 5.5 + Vite 5.4 + Vitest 2 + ESLint 9 flat + Prettier 3; 3 pagine, 6 componenti, `api/client.ts` + `api/claims.ts`, `types/claims.ts`, `utils/format.ts`, 3 test, `vite.config.ts` (dev **5199**, preview **5198**, proxy `/bff`→8185, `strictPort`), `dist/` già prodotto                                                                              |
| Infra DB         | `infra/db/init/01-init-schemas.sql` (schema `coreapp` + `documents`, **nessuna tabella**, idempotente)                                                                                                                                                                                                                                                                 |
| Docs             | `docs/db/README.md` (contratto DB, §6 Flyway contract), `docs/plan/TK-DE445B-blueprint.md`                                                                                                                                                                                                                                                                             |
| API contracts    | `docs/api/core-app.openapi.yaml`, `docs/api/document-service.openapi.yaml`, `docs/api/bff.openapi.yaml` — **consegnati da questo piano**, scritti riga per riga sui controller reali e validati come YAML                                                                                                                                                              |

### 0.2 Assente (perimetro di questo piano)

| Gruppo            | File mancanti                                                                                                                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Governance root   | `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `VERSION`, `CHANGELOG.md`, `.editorconfig`, `.gitattributes`, `.prettierrc.json`, `.prettierignore`, `.env.example`, `.gitleaks.toml`, `.releaserc.json`, `.dockerignore` |
| GitHub            | **tutta la directory `.github/`**: `workflows/*.yml` (7), `CODEOWNERS`, `PULL_REQUEST_TEMPLATE.md`, `ISSUE_TEMPLATE/*` (3), `dependabot.yml`                                                                             |
| Infra container   | `infra/docker/*.Dockerfile` (4), `infra/docker/nginx/default.conf`, `infra/docker-compose.yml`, `infra/scripts/*.sh` (3), `infra/k8s/base/**` (6), `infra/README.md`                                                     |
| Docs architettura | `docs/architecture/README.md`, `docs/architecture/adr/0001..0012`, `docs/versioning.md`, `docs/ci.md`                                                                                                                    |

### 0.3 Toolchain (ri-verificata in questa sessione)

| Tool                                       | Stato                                                             | Conseguenza                                                                |
| ------------------------------------------ | ----------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Java                                       | **21.0.11** presente                                              | build/test locali ok                                                       |
| Maven                                      | `mvn` presente (usare `mvn` in locale; `./mvnw` scarica la 3.9.9) | ok                                                                         |
| Node / npm / npx                           | **20.20.2** / 10.x / presente                                     | build, lint, test FE ok                                                    |
| `frontend/node_modules`                    | **presente**, include **`js-yaml`**                               | validazione YAML **offline** possibile (vedi §9)                           |
| `docker`, `docker-compose`                 | **ASSENTI**                                                       | `docker build`/`compose up` **non eseguibili**: verifica solo statica + CI |
| `cosign`, `syft`, `trivy`                  | **ASSENTI**                                                       | firma e SBOM verificabili **solo in CI**                                   |
| `hadolint`, `actionlint`, `yamllint`, `jq` | **ASSENTI**                                                       | lint Dockerfile/workflow solo in CI (o assente, fase 1)                    |
| Rete                                       | Maven Central + npmjs raggiungibili                               | `npm ci` e dipendenze Maven risolvibili in locale                          |

### 0.4 Osservazione di sicurezza (non bloccante, ma da gestire)

`git config remote.origin.url` contiene il **PAT in chiaro** dentro `.git/config` (file **non tracciato**, quindi AC-12 sul contenuto del repo resta rispettato). Regole che ne derivano, vincolanti:

- **P-1**: nessun agente deve copiare/incollare quella URL in file del repo, in report, in issue o in log committati.
- **P-2**: ogni nuovo documento che mostra il remote deve usare la forma `https://github.com/France012/test_newera.git` (senza credenziali).
- **P-3**: in CI l'autenticazione usa `GITHUB_TOKEN` (OIDC), mai un PAT in chiaro nei workflow.

---

## 1. Gap analysis rispetto ai criteri di accettazione

| AC    | Requisito                                      | Stato attuale                                                                    | Chi lo chiude                   |
| ----- | ---------------------------------------------- | -------------------------------------------------------------------------------- | ------------------------------- |
| AC-1  | 5 moduli separati                              | **MET** (`core-app`, `document-service`, `bff`, `frontend`, `infra`)             | —                               |
| AC-2  | Build Java 21 coerente + lint/format condivisi | **MET** a codice (spotless+checkstyle in `verify`) ma **non verificabile in CI** | Wave E (workflow `ci.yml`)      |
| AC-3  | Lint/format frontend                           | **MET** (`lint`, `format:check`, `typecheck` negli script)                       | —                               |
| AC-4  | CI con build + unit test + lint                | **NOT MET** (`.github/` assente)                                                 | E-CI-1                          |
| AC-5  | SCA                                            | **NOT MET**                                                                      | E-CI-2 (ADR-0006)               |
| AC-6  | SAST                                           | **NOT MET**                                                                      | E-CI-3                          |
| AC-7  | Secret scanning                                | **NOT MET** (manca workflow e `.gitleaks.toml`)                                  | E-A-6, E-CI-4 (ADR-0010)        |
| AC-8  | Docker multi-stage per servizio                | **NOT MET** (nessun Dockerfile)                                                  | E-INF-1..4                      |
| AC-9  | Immagini firmate                               | **NOT MET**                                                                      | E-CI-5 (ADR-0003, ADR-0007)     |
| AC-10 | SBOM CycloneDX come artefatto                  | **NOT MET** (plugin configurato, nessun job che lo esegue)                       | E-CI-5                          |
| AC-11 | `main` builda e testa ad ogni push             | **NOT MET**                                                                      | E-CI-1 + sblocco permessi token |
| AC-12 | Nessun segreto in chiaro                       | **MET** nel contenuto tracciato (grep pulito) — va **presidiato**                | E-A-6 + gate `repo-hygiene`     |
| AC-13 | PR template, CODEOWNERS, versioning            | **NOT MET**                                                                      | E-A-8..A-12, E-A-14..A-17       |

---

## 2. Decisioni vincolanti residue (ADR addendum)

Le ADR-0001..0005 sono già decise nello blueprint §2.2: vanno solo **trascritte** in `docs/architecture/adr/` (nessuna nuova analisi). Le seguenti sono **nuove** e nascono da scarti fra blueprint e realtà: sono vincolanti.

### ADR-0006 — SCA: Trivy come gate primario, OWASP Dependency-Check settimanale

- **Contesto**: `dependency-check-maven` scarica il feed NVD (minuti, e rate-limited senza `NVD_API_KEY`); su ogni push renderebbe la CI lenta e fragile.
- **Decisione**: (a) su **ogni** push/PR → `aquasecurity/trivy-action` in modalità `fs` (copre `pom.xml` + `package-lock.json`), SARIF → code scanning, `exit-code: 0` in fase 1 (report-only: non si blocca una PR per CVE transitive senza fix disponibile); (b) `npm audit --audit-level=critical` **bloccante**, `high` riportato; (c) OWASP Dependency-Check **solo** su `schedule` (settimanale) e `workflow_dispatch`, con `NVD_API_KEY` opzionale, `--failOnCVSS 9`.
- **Conseguenze**: AC-5 soddisfatto con due livelli; il gate bloccante è documentato in `docs/ci.md`; nessun segreto obbligatorio (NVD opzionale).

### ADR-0007 — Runtime container: nginx _unprivileged_ + healthcheck con `wget`

- **Contesto**: `eclipse-temurin:21-jre-alpine` **non** contiene `curl` (busybox `wget` sì); `nginx:1.27-alpine` parte come root.
- **Decisione**: `HEALTHCHECK` con `wget -q -O - http://127.0.0.1:<port>/actuator/health`; per il frontend base image **`nginxinc/nginx-unprivileged:1.27-alpine`** (uid 101, già non-root, `pid`/temp in `/tmp`) e `listen 8188` (>1024, compatibile con utente non privilegiato).
- **Conseguenze**: nessun container gira come root (requisito di sicurezza, e `HEALTHCHECK` funziona davvero). Il Dockerfile frontend **non** usa `nginx:alpine` + `USER nginx`, che fallirebbe sulla porta di default 80.

### ADR-0008 — Una sola installazione di Prettier, nessun `package.json` alla root

- **Contesto**: Prettier serve anche per `*.md`, `.github/**/*.yml`, `infra/*.yml`; creare un `package.json` root introdurrebbe un secondo lockfile e un secondo `npm ci` in CI.
- **Decisione**: `frontend/` resta l'unico progetto Node; la CI usa il binario `./frontend/node_modules/.bin/prettier --check .` **dalla root**, con `.prettierrc.json` + `.prettierignore` alla root che ignorano `frontend/` (che mantiene il proprio `.prettierrc.json`). Le due configurazioni **devono restare identiche** (`printWidth 100`, `singleQuote`, `semi`, `trailingComma all`).
- **Conseguenze**: un solo `npm ci` per job, zero drift di dipendenze Node.

### ADR-0009 — Contratti OpenAPI come snapshot versionato + gate di parsing in CI

- **Contesto**: springdoc genera il documento a runtime, ma nessuno verifica che il contratto committato sia YAML valido.
- **Decisione**: i 3 file `docs/api/*.openapi.yaml` (consegnati con questo piano, allineati ai controller reali) sono la **reviewed snapshot**; il job `repo-hygiene` di `ci.yml` ne verifica la **parsabilità** con `js-yaml` preso da `frontend/node_modules` (nessuna dipendenza di rete aggiuntiva). Il drift codice↔contratto resta responsabilità della review umana (checklist nel PR template).
- **Conseguenze**: AC soddisfatto offline; niente `swagger-cli`/`redocly` da scaricare.

### ADR-0010 — Secret scanning a due livelli (nessun segreto obbligatorio)

- **Contesto**: `gitleaks/gitleaks-action@v2` richiede `GITLEAKS_LICENSE` per repo di **organizzazione** private; il repo è privato ma user-owned (probabilmente esente). Non si può dipendere da un segreto per soddisfare AC-7.
- **Decisione**: (a) `secret-scan.yml` = gitleaks-action con `fetch-depth: 0` e `.gitleaks.toml`; se il runner chiede la licenza, il segreto `GITLEAKS_LICENSE` è documentato come **opzionale** in `docs/ci.md`; (b) **gate sempre attivo e indipendente** dentro `ci.yml` (`repo-hygiene`): `grep` sui pattern `ghp_…`, `github_pat_…`, `AKIA…`, `-----BEGIN … PRIVATE KEY-----`, che deve uscire **vuoto** su ogni push.
- **Conseguenze**: AC-7 non è ostaggio di una licenza esterna; il gate grep è ridondante ma deterministico.

### ADR-0011 — Naming e tagging delle immagini

- **Decisione**: immagini `ghcr.io/<owner-lowercase>/<repo-lowercase>/<servizio>` (owner/repo **lowercased** in uno step dedicato: GHCR rifiuta le maiuscole — `France012` va normalizzato); tag prodotti da `docker/metadata-action`: semver su tag `v*` (`0.1.0`, `0.1`), `sha-<short>` su ogni push, `latest` solo su `main`. `provenance: false` su `build-push-action` (la provenienza è attestata esplicitamente da `actions/attest-build-provenance`, altrimenti cosign firma un manifest che non è quello atteso).
- **Fallback nome**: se GHCR rifiuta `test_newera` (underscore), usare `test-newera` come solo path dell'immagine e annotarlo in `docs/ci.md` — **il nome del repository GitHub non va cambiato**.

### ADR-0012 — SpotBugs e `suppressions.xml` rimandati (deviazione consapevole dal blueprint §A16/A17)

- **Contesto**: il root `pom.xml` implementato **non** contiene SpotBugs; `config/checkstyle/suppressions.xml` non è referenziato da nessun POM.
- **Decisione**: **non** creare `config/spotbugs/exclude.xml` né `config/checkstyle/suppressions.xml` in questa iterazione (sarebbero configurazione morta). La copertura di sicurezza statica è data da CodeQL (SAST) + Trivy/OWASP (SCA). SpotBugs è registrato come debito tecnico in `docs/ci.md §Futuro`.
- **Conseguenze**: il build non cambia comportamento; nessun rischio di rottura su codice já formattato da google-java-format.

---

## 3. Requisiti: Acceptance Criteria misurabili (aggiunte a blueprint §1.1)

Valgono gli AC-1..AC-13 del blueprint. Per il lavoro residuo diventano:

| ID    | Criterio verificabile                         | Comando/evidenza                                                                                                                                 |
| ----- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| AC-E1 | `main` builda e testa ad ogni push/PR         | `ci.yml` con trigger `push [main]` + `pull_request`; job `java-verify`, `frontend-ci`, `repo-hygiene` tutti verdi; check aggregato `ci-success`  |
| AC-E2 | SCA attivo                                    | `dependency-scan.yml` produce ≥1 SARIF caricato + artefatto `npm-audit.json`                                                                     |
| AC-E3 | SAST attivo                                   | `codeql.yml` con matrix `java-kotlin` + `javascript-typescript`, entrambe `analyze` verdi                                                        |
| AC-E4 | Secret scanning attivo                        | `secret-scan.yml` verde + gate grep in `ci.yml` vuoto                                                                                            |
| AC-E5 | 4 immagini multi-stage, non-root, healthcheck | 4 Dockerfile in `infra/docker/`, ognuno con ≥2 `FROM`, `USER` non-root, `HEALTHCHECK`, `EXPOSE` coerente con §2.9 del blueprint                  |
| AC-E6 | Immagini firmate                              | `container-images.yml`: `cosign sign --yes <image>@<digest>` + `attest-build-provenance`, permessi `id-token: write` + `attestations: write`     |
| AC-E7 | SBOM artefatto                                | per ogni immagine `sbom-<servizio>.cdx.json` + aggregato Maven `sbom-claims-platform.json` caricati con `actions/upload-artifact@v4`             |
| AC-E8 | Nessun segreto                                | grep vuoto (§9) + `.gitignore` + `.env.example` con soli placeholder                                                                             |
| AC-E9 | Governance                                    | `CODEOWNERS`, `PULL_REQUEST_TEMPLATE.md`, 3 `ISSUE_TEMPLATE`, `dependabot.yml`, `VERSION`, `CHANGELOG.md`, `.releaserc.json` presenti e coerenti |

### Definition of Done (aggiunta locale)

- [ ] `mvn -B -ntp verify` da root → `BUILD SUCCESS` (non deve regredire: nessun file Java viene toccato da questo piano).
- [ ] `npm --prefix frontend ci && npm --prefix frontend run lint && npm --prefix frontend run format:check && npm --prefix frontend run test:run && npm --prefix frontend run build` → exit 0.
- [ ] `./frontend/node_modules/.bin/prettier --check .` da root → "All matched files use Prettier code style".
- [ ] Tutti i file YAML (`.github/workflows/*.yml`, `infra/docker-compose.yml`, `docs/api/*.yaml`) **parsabili** con js-yaml.
- [ ] `sh -n` pulito su ogni `infra/scripts/*.sh`; bit di esecuzione `100755` su di essi.
- [ ] `git status --porcelain` vuoto dopo il commit; nessun file in `target/`, `node_modules/`, `dist/`, `sbom/`.
- [ ] Nessuna occorrenza di PAT/token nei file tracciati (§0.4 P-1).

---

## 4. Manifest — Wave A-remainder (governance e igiene)

> Owner suggerito: **hygiene-agent**. Prerequisito di tutto il resto (i workflow referenziano `VERSION`, `.gitleaks.toml`, `.prettier*`).
> Base path: `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa/`

| #      | Path esatto (CREATE)                                                                               | Contenuto richiesto (spec)                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ------ | -------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| E-A-1  | `.gitattributes`                                                                                   | `* text=auto eol=lf`; `mvnw text eol=lf`; `mvnw.cmd text eol=crlf`; `*.sh text eol=lf`; `*.jar binary`; `*.png binary`; `*.gif binary`                                                                                                                                                                                                                                                                                                               |
| E-A-2  | `.editorconfig`                                                                                    | `root=true`; `[*]` charset utf-8, eol lf, final newline, trim trailing whitespace; `[*.{java,xml}]` indent 4; `[*.{ts,tsx,js,json,yml,yaml,css}]` indent 2; `[*.md]` `trim_trailing_whitespace=false`                                                                                                                                                                                                                                                |
| E-A-3  | `.prettierrc.json`                                                                                 | `printWidth:100`, `singleQuote:true`, `semi:true`, `trailingComma:"all"`, `arrowParens:"always"`, `endOfLine:"lf"`. **Identico a `frontend/.prettierrc.json`** (ADR-0008)                                                                                                                                                                                                                                                                            |
| E-A-4  | `.prettierignore`                                                                                  | `frontend/`, `node_modules/`, `**/target/`, `**/dist/`, `.git/`, `sbom/`, `*.jar`, `package-lock.json`                                                                                                                                                                                                                                                                                                                                               |
| E-A-5  | `.env.example` (root)                                                                              | Solo placeholder, con commento `# __change_me__`. Deve documentare **esattamente** le variabili in §4.1                                                                                                                                                                                                                                                                                                                                              |
| E-A-6  | `.gitleaks.toml`                                                                                   | `[extend] useDefault = true`; `[[rules]]` custom opzionale per `github_pat_`; allowlist `paths`: `.env.example`, `docs/plan/.*\.md` (contiene il pattern grep documentato), `frontend/src/**/*.test.ts`, `**/*.example`                                                                                                                                                                                                                              |
| E-A-7  | `.releaserc.json`                                                                                  | `branches:["main"]`, `tagFormat:"v${version}"`, plugin: `commit-analyzer` (preset `conventionalcommits`), `release-notes-generator`, `changelog` (`CHANGELOG.md`), `npm` con `{"npmPublish": false}`, `github` (assets `CHANGELOG.md`), `git` (assets `CHANGELOG.md`)                                                                                                                                                                                |
| E-A-8  | `VERSION`                                                                                          | Contenuto: `0.1.0` (una riga, newline finale) — **deve restare allineato** a `<version>` del root `pom.xml`                                                                                                                                                                                                                                                                                                                                          |
| E-A-9  | `CHANGELOG.md`                                                                                     | Keep a Changelog 1.1.0 + SemVer; sezione `## [Unreleased]`; sezione `## [0.1.0]` con Added/Changed/Security che riassume il monorepo (scaffolding, 3 servizi, frontend, contratti API, DB)                                                                                                                                                                                                                                                           |
| E-A-10 | `README.md` (root)                                                                                 | Badge (CI, CodeQL, license); diagramma `mermaid` della vista logica (blueprint §2.1); tabella **porte 8180/8181/8185/8188/5199/5198/5442**; quickstart backend (`mvn -B -ntp verify`), frontend (`npm --prefix frontend ci && npm run dev`), docker (`docker compose -f infra/docker-compose.yml up --build`); tabella comandi lint/test/build; link a `docs/api/`, `docs/db/`, `docs/ci.md`, `docs/versioning.md`, `CONTRIBUTING.md`, `SECURITY.md` |
| E-A-11 | `CONTRIBUTING.md`                                                                                  | Conventional Commits + tipi ammessi; branch `feat                                                                                                                                                                                                                                                                                                                                                                                                    | fix | chore | docs/TK-xxx-slug`; flusso PR (branch→PR template→review CODEOWNERS→squash); DoD locale (i 5 comandi di §9); code style (spotless:apply, `npm run lint:fix`, `npm run format`); divieto di commit di segreti/`.env`; come aggiornare `docs/api/*` |
| E-A-12 | `SECURITY.md`                                                                                      | Canali di segnalazione; policy segreti (env/OIDC, mai in repo, `.env.example` placeholder); strumenti attivi (CodeQL, Trivy, OWASP DC, gitleaks) e cadenze; versioni supportate (0.1.x); riferimento ADR-0003 (cosign keyless)                                                                                                                                                                                                                       |
| E-A-13 | `.github/CODEOWNERS`                                                                               | `* @France012`; righe per `/core-app/`, `/document-service/`, `/bff/`, `/frontend/`, `/infra/`, `/.github/`, `/docs/` tutte su `@France012`                                                                                                                                                                                                                                                                                                          |
| E-A-14 | `.github/PULL_REQUEST_TEMPLATE.md`                                                                 | Sezioni: Ticket (`TK-xxx`), Tipo (feature/fix/chore/docs), **Checklist AC** (spuntabile, con richiamo ad AC-1..AC-13), Impatto API (link ai 3 `docs/api`), Impatto DB (migrazione Flyway sì/no), Evidenze verifica (comandi §9 eseguiti + output), Conferma no-segreti                                                                                                                                                                               |
| E-A-15 | `.github/ISSUE_TEMPLATE/bug_report.yml`                                                            | Form: titolo, descrizione, passi di riproduzione, atteso vs ottenuto, ambiente (porte/versioni), log                                                                                                                                                                                                                                                                                                                                                 |
| E-A-16 | `.github/ISSUE_TEMPLATE/feature_request.yml`                                                       | Form: problema, proposta, AC proposti, impatto moduli                                                                                                                                                                                                                                                                                                                                                                                                |
| E-A-17 | `.github/ISSUE_TEMPLATE/config.yml`                                                                | `blank_issues_enabled: false` + link a `SECURITY.md` per le vulnerabilità                                                                                                                                                                                                                                                                                                                                                                            |
| E-A-18 | `.github/dependabot.yml`                                                                           | `version: 2`; ecosistemi: `maven` (`/`, weekly, `groups` spring), `npm` (`/frontend`, weekly), `github-actions` (`/`, weekly), `docker` (`/infra/docker`, weekly). `open-pull-requests-limit: 5`                                                                                                                                                                                                                                                     |
| E-A-19 | `docs/versioning.md`                                                                               | SemVer 2.0; `VERSION` ↔ root pom; come bumpare (`mvn -N versions:set -DnewVersion=X`, `mvn versions:commit`); tag `vX.Y.Z`; cosa fa semantic-release; schema tag GHCR (ADR-0011)                                                                                                                                                                                                                                                                     |
| E-A-20 | `docs/ci.md`                                                                                       | Mappa workflow→trigger→permessi→artefatti; segreti richiesti/opzionali (`NVD_API_KEY` opzionale, `GITLEAKS_LICENSE` opzionale, `GITHUB_TOKEN` implicito); come riprodurre in locale (§9) e **cosa non è verificabile in locale** (docker assente); debito tecnico (SpotBugs, ADR-0012)                                                                                                                                                               |
| E-A-21 | `docs/architecture/README.md`                                                                      | C4-lite `mermaid` (context + container) + tabella moduli/responsabilità/porte + elenco dipendenze fra moduli (**nessuna** dipendenza Maven fra i 3 servizi: solo HTTP)                                                                                                                                                                                                                                                                               |
| E-A-22 | `docs/architecture/adr/0001-monorepo-maven-multi-module.md` … `0005-security-baseline-deferred.md` | Trascrizione **1:1** delle righe di blueprint §2.2 pertinenti (contesto/decisione/conseguenze). Nessuna nuova analisi                                                                                                                                                                                                                                                                                                                                |
| E-A-23 | `docs/architecture/adr/0006-…` … `0012-spotbugs-deferred.md`                                       | Trascrizione **1:1** del §2 di questo piano                                                                                                                                                                                                                                                                                                                                                                                                          |

### 4.1 Variabili d'ambiente — contratto `.env.example`

Estratte dai file realmente presenti (graffa `${VAR:default}`), quindi vincolanti:

| Variabile                                        | Consumata da                        | Esempio/placeholder                                                 |
| ------------------------------------------------ | ----------------------------------- | ------------------------------------------------------------------- |
| `POSTGRES_USER`                                  | core-app, document-service, compose | `claims`                                                            |
| `POSTGRES_PASSWORD`                              | idem                                | `__change_me__`                                                     |
| `POSTGRES_DB`                                    | compose (postgres image)            | `claims`                                                            |
| `CORE_APP_DB_URL`                                | core-app                            | `jdbc:postgresql://localhost:5442/claims`                           |
| `CORE_APP_DB_POOL_SIZE`                          | core-app                            | `10`                                                                |
| `DOCUMENT_SERVICE_DB_URL`                        | document-service                    | `jdbc:postgresql://localhost:5442/claims`                           |
| `DOCUMENT_SERVICE_DB_POOL_SIZE`                  | document-service                    | `10`                                                                |
| `DOCUMENTS_STORAGE_PATH`                         | document-service                    | `./target/storage` (container: `/var/lib/documents`)                |
| `CORE_APP_BASE_URL`                              | bff                                 | `http://localhost:8180` (container: `http://core-app:8180`)         |
| `DOCUMENT_SERVICE_BASE_URL`                      | bff                                 | `http://localhost:8181` (container: `http://document-service:8181`) |
| `BFF_ALLOWED_ORIGINS`                            | bff                                 | `http://localhost:5199` (**mai** `*`)                               |
| `BFF_CONNECT_TIMEOUT_MS` / `BFF_READ_TIMEOUT_MS` | bff                                 | `2000` / `5000`                                                     |
| `VITE_BFF_BASE_URL`                              | frontend                            | **vuoto** (same-origin via proxy)                                   |
| `VITE_PROXY_TARGET`                              | frontend (dev)                      | `http://localhost:8185`                                             |

Regola: `.env.example` **non** contiene `POSTGRES_PASSWORD` reale né alcun token; `.env` resta git-ignorato.

---

## 5. Manifest — Wave E (infra container + CI/CD)

> Owner suggerito: **infra-agent** (E-INF) e **ci-agent** (E-CI). Prerequisito: Wave A-remainder.
> Base path: `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa/`

### 5.1 Container e orchestrazione locale

| #        | Path esatto (CREATE)                       | Contenuto richiesto (spec)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| -------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| E-INF-1  | `.dockerignore` (root)                     | `.git`, `**/target`, `**/node_modules`, `**/dist`, `sbom`, `*.log`, `.env*`, `docs/plan`, `**/*.md` (esclusi i README se servono alla build: non servono)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| E-INF-2  | `infra/docker/core-app.Dockerfile`         | `# syntax=docker/dockerfile:1.7`; **stage `build`** `FROM maven:3.9.9-eclipse-temurin-21 AS build`, `WORKDIR /workspace`; `COPY pom.xml ./` + i **3** `pom.xml` di modulo + `COPY config/ config/` (i plugin checkstyle/spotless risolvono `${maven.multiModuleProjectDirectory}/config`); `RUN mvn -B -ntp -pl core-app -am -DskipTests package`; **stage `runtime`** `FROM eclipse-temurin:21-jre-alpine`, `addgroup -g 10001 -S app && adduser -u 10001 -S -G app app`, `COPY --from=build /workspace/core-app/target/*.jar /app/app.jar`, `USER 10001:10001`, `EXPOSE 8180`, `HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 CMD wget -q -O - http://127.0.0.1:8180/actuator/health \|\| exit 1`, `ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]`                               |
| E-INF-3  | `infra/docker/document-service.Dockerfile` | Idem, `-pl document-service -am`, `EXPOSE 8181`, `ENV DOCUMENTS_STORAGE_PATH=/var/lib/documents`, `RUN mkdir -p /var/lib/documents && chown 10001:10001 /var/lib/documents`, `VOLUME ["/var/lib/documents"]`, healthcheck su 8181                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| E-INF-4  | `infra/docker/bff.Dockerfile`              | Idem, `-pl bff -am`, `EXPOSE 8185`, healthcheck su 8185                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| E-INF-5  | `infra/docker/frontend.Dockerfile`         | Stage `build`: `FROM node:20-alpine AS build`, `WORKDIR /app`, `COPY frontend/package.json frontend/package-lock.json ./`, `RUN npm ci`, `COPY frontend/ ./`, `RUN npm run build`; stage `runtime`: **`FROM nginxinc/nginx-unprivileged:1.27-alpine`**, `COPY infra/docker/nginx/default.conf /etc/nginx/conf.d/default.conf`, `COPY --from=build /app/dist /usr/share/nginx/html`, `EXPOSE 8188`, `USER 101` (ADR-0007). Nessun `docker build` locale possibile: la correttezza è verificata in CI                                                                                                                                                                                                                                                                                                                           |
| E-INF-6  | `infra/docker/nginx/default.conf`          | `server { listen 8188; server_name _; root /usr/share/nginx/html; index index.html; ... }`; **`location /bff/ { proxy_pass http://bff:8185; }` senza slash finale** (il path `/bff/v1/**` deve arrivare intatto al BFF); `location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; }`; `location / { try_files $uri $uri/ /index.html; }` (SPA fallback); header sicurezza `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Content-Security-Policy` minima (`default-src 'self'`); `gzip on`                                                                                                                                                                                                                                                                                      |
| E-INF-7  | `infra/docker-compose.yml`                 | **Nessuna chiave `version:`** (obsoleta). `name: claims-platform`. Servizi: `postgres` (`postgres:16-alpine`, `ports: ["5442:5432"]`, `environment` da `${POSTGRES_USER}`/`${POSTGRES_PASSWORD}`/`POSTGRES_DB=claims`, volume `pgdata`, mount `./db/init:/docker-entrypoint-initdb.d:ro` — path relativo a `infra/`, healthcheck `pg_isready`), `core-app` (`build: {context: .., dockerfile: infra/docker/core-app.Dockerfile}`, porta 8180, `depends_on: postgres: condition: service_healthy`), `document-service` (8181, volume `documents_data:/var/lib/documents`), `bff` (8185, `CORE_APP_BASE_URL=http://core-app:8180`, `DOCUMENT_SERVICE_BASE_URL=http://document-service:8181`), `frontend` (8188). Ogni servizio: `healthcheck` e `restart: unless-stopped`. **Nessun valore di segreto nel file**: solo `${VAR}` |
| E-INF-8  | `infra/scripts/build-images.sh`            | POSIX `sh` con `set -eu`; legge `VERSION` e `git rev-parse --short HEAD`; build dei 4 Dockerfile con `--tag ghcr.io/...:<version>` e `:<sha>`; messaggi in inglese; `chmod 755`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| E-INF-9  | `infra/scripts/generate-sbom.sh`           | `mvn -B cyclonedx:makeAggregateBom` → `target/sbom-claims-platform.json`; (se `syft` presente) un SBOM per immagine; degrada con messaggio chiaro se `syft` manca                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| E-INF-10 | `infra/scripts/sign-images.sh`             | `cosign sign --yes <image>@<digest>` per ogni immagine; richiede `cosign` + login; fallisce con messaggio esplicito se `cosign` assente                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| E-INF-11 | `infra/k8s/base/namespace.yaml`            | `Namespace claims-platform` con label `app.kubernetes.io/part-of`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| E-INF-12 | `infra/k8s/base/core-app.yaml`             | `Deployment` (`image` con **digest placeholder**, `resources.requests/limits`, `readinessProbe`/`livenessProbe` su `/actuator/health` porta 8180, `securityContext` `runAsNonRoot: true`, `runAsUser: 10001`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`) + `Service` ClusterIP 8180                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| E-INF-13 | `infra/k8s/base/document-service.yaml`     | Idem, 8181, `emptyDir`/PVC su `/var/lib/documents`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| E-INF-14 | `infra/k8s/base/bff.yaml`                  | Idem, 8185, env `CORE_APP_BASE_URL`/`DOCUMENT_SERVICE_BASE_URL` da `ConfigMap`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| E-INF-15 | `infra/k8s/base/frontend.yaml`             | Idem, 8188, `readOnlyRootFilesystem: true` + `emptyDir` su `/tmp` e `/var/cache/nginx`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| E-INF-16 | `infra/k8s/base/kustomization.yaml`        | `resources:` i 5 file sopra + `commonLabels`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| E-INF-17 | `infra/README.md`                          | Mappa infra, comandi (`docker compose -f infra/docker-compose.yml up --build`), prerequisiti, tabella porte, nota **"docker non è disponibile nell'ambiente di sviluppo: validazione statica + CI"**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

### 5.2 Pipeline (GitHub Actions)

| #      | Path esatto (CREATE)                     | Sintesi (dettaglio in §6)                                                        |
| ------ | ---------------------------------------- | -------------------------------------------------------------------------------- |
| E-CI-1 | `.github/workflows/ci.yml`               | job `java-verify`, `frontend-ci`, `repo-hygiene`, `ci-success`                   |
| E-CI-2 | `.github/workflows/dependency-scan.yml`  | `trivy-fs` (SARIF), `npm-audit`, `owasp-dependency-check` (solo schedule/manual) |
| E-CI-3 | `.github/workflows/codeql.yml`           | matrix `java-kotlin` + `javascript-typescript`                                   |
| E-CI-4 | `.github/workflows/secret-scan.yml`      | gitleaks-action, `fetch-depth: 0`                                                |
| E-CI-5 | `.github/workflows/container-images.yml` | matrix 4 immagini: buildx → GHCR → SBOM → cosign → provenance                    |
| E-CI-6 | `.github/workflows/release.yml`          | semantic-release (tag + CHANGELOG + GitHub Release)                              |
| E-CI-7 | `.github/workflows/pr-title.yml`         | `amannn/action-semantic-pull-request@v5`                                         |

---

## 6. Specifica CI in dettaglio (vincolante)

Regole comuni a **tutti** i workflow:

- `permissions:` **minimi** in testa al file (default `contents: read`), override per job solo dove serve.
- `concurrency: { group: '<workflow>-${{ github.ref }}', cancel-in-progress: true }`.
- `timeout-minutes` esplicito su ogni job (nessuno job può appendere la coda).
- Runner `ubuntu-latest`; action pinnate a **major tag** (`@v4`/`@v3`) e aggiornate da Dependabot (E-A-18).
- Nessun segreto nei file: solo `${{ secrets.* }}` / `${{ github.token }}`.
- `actions/upload-artifact@v4` con `retention-days: 7` (30 per gli SBOM).

### 6.1 `ci.yml` — AC-4, AC-11 (+ gate AC-7/AC-12, ADR-0009)

```yaml
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
  workflow_dispatch:
permissions: { contents: read }
```

| Job                         | Step chiave                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `java-verify` (timeout 25)  | `actions/checkout@v4` → `actions/setup-java@v4` (`temurin`, `21`, `cache: maven`, `cache-dependency-path: '**/pom.xml'`) → `./mvnw -B -ntp verify` → `if: always()` `upload-artifact` di `**/target/surefire-reports` + `**/target/checkstyle-result.xml`                                                                                                                                                                                                                                              |
| `frontend-ci` (timeout 20)  | `checkout` → `actions/setup-node@v4` (`node-version: '20'`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`) → `npm ci` (working-directory `frontend`) → `npm run lint` → `npm run format:check` → **`npm run test:run`** → `npm run build` → `upload-artifact` `frontend/dist`                                                                                                                                                                                                      |
| `repo-hygiene` (timeout 10) | `checkout` → `npm ci` (frontend) → `./frontend/node_modules/.bin/prettier --check .` (dalla root, ADR-0008) → parsing YAML con `node` + `js-yaml` su `.github/workflows/*.yml`, `infra/docker-compose.yml`, `docs/api/*.yaml` → **gate segreti** `grep -rniE '(ghp_[A-Za-z0-9]{20,}\|github_pat_[A-Za-z0-9_]{20,}\|AKIA[0-9A-Z]{16}\|-----BEGIN [A-Z ]*PRIVATE KEY-----)' . --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist` con esito che deve essere **vuoto** |
| `ci-success`                | `needs: [java-verify, frontend-ci, repo-hygiene]`, `if: always()`, fallisce se un need non è `success` → **unico check da richiedere in branch protection**                                                                                                                                                                                                                                                                                                                                            |

⚠️ **Pitfall P-1**: `frontend/package.json` espone `test:run` (non `test -- --run`). Usare `npm run test:run`, altrimenti Vitest entra in watch mode e il job si blocca fino al timeout.
⚠️ **Pitfall P-2**: `npm run build` esegue già `typecheck`: non aggiungere un passo `tsc` separato (spreco + doppio errore).

### 6.2 `dependency-scan.yml` — AC-5 (ADR-0006)

```yaml
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
  schedule: [{ cron: '0 3 * * 1' }] # lunedì 03:00 UTC
  workflow_dispatch:
permissions: { contents: read, security-events: write }
```

- `trivy-fs`: `aquasecurity/trivy-action` (pin ad una release `0.2x` stabile) `scan-type: fs`, `scan-ref: .`, `format: sarif`, `output: trivy-results.sarif`, `severity: CRITICAL,HIGH`, `ignore-unfixed: true`, `exit-code: '0'` → `github/codeql-action/upload-sarif@v3`.
- `npm-audit`: node 20, `npm ci`, `npm audit --audit-level=critical` (**bloccante**), poi `npm audit --json > npm-audit.json || true` + `upload-artifact`.
- `owasp-dependency-check`: `if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'`; `dependency-check/Dependency-Check_Action` con `--failOnCVSS 9 --nvdApiKey ${{ secrets.NVD_API_KEY }}` (segreto **opzionale**: se assente il passo è più lento ma funziona); upload SARIF + report HTML.

### 6.3 `codeql.yml` — AC-6

```yaml
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
  schedule: [{ cron: '0 5 * * 2' }]
permissions: { contents: read, security-events: write, packages: read, actions: read }
strategy: { fail-fast: false, matrix: { language: [java-kotlin, javascript-typescript] } }
```

- `checkout` → se `java-kotlin`: `setup-java@v4` (21) e build manuale `./mvnw -B -ntp -DskipTests package`; se `javascript-typescript`: nessun build.
- `codeql-action/init@v3` con `build-mode: manual` (java) / `none` (js) → `codeql-action/analyze@v3` con `category: /language:${{ matrix.language }}`.

### 6.4 `secret-scan.yml` — AC-7 (ADR-0010)

```yaml
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
  schedule: [{ cron: '0 6 * * 3' }]
permissions: { contents: read }
```

- `checkout` con **`fetch-depth: 0`** (serve la storia completa).
- `gitleaks/gitleaks-action@v2` con `env: GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` e, se disponibile, `GITLEAKS_LICENSE: ${{ secrets.GITLEAKS_LICENSE }}`. Config `.gitleaks.toml` rilevata automaticamente.
- Se il runner richiede la licenza e il segreto non c'è: documentarlo in `docs/ci.md` **e** nessun cambio di piano (il gate grep in `ci.yml` resta comunque attivo).

### 6.5 `container-images.yml` — AC-8, AC-9, AC-10 (ADR-0007, ADR-0011)

```yaml
on:
  push:
    branches: [main]
    tags: ['v*']
  workflow_dispatch:
permissions:
  contents: read
  packages: write
  id-token: write # cosign keyless (OIDC)
  attestations: write # attest-build-provenance
```

Step per ogni immagine (`strategy.matrix`, `fail-fast: false`):

| #   | Step                                        | Dettaglio                                                                                                                                                                                                                                                                           |
| --- | ------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `checkout@v4`                               | —                                                                                                                                                                                                                                                                                   |
| 2   | **normalizza il nome**                      | `echo "OWNER=$(echo '${{ github.repository_owner }}' \| tr '[:upper:]' '[:lower:]')" >> $GITHUB_ENV` e `echo "IMAGE_BASE=ghcr.io/$OWNER/$(echo '${{ github.repository }}' \| cut -d/ -f2 \| tr '[:upper:]' '[:lower:]')" >> $GITHUB_ENV` — **GHCR rifiuta le maiuscole** (ADR-0011) |
| 3   | `docker/setup-buildx-action@v3`             | —                                                                                                                                                                                                                                                                                   |
| 4   | `docker/login-action@v3`                    | `registry: ghcr.io`, `username: ${{ github.actor }}`, `password: ${{ secrets.GITHUB_TOKEN }}`                                                                                                                                                                                       |
| 5   | `docker/metadata-action@v5`                 | `images: ${{ env.IMAGE_BASE }}/${{ matrix.service }}`; `tags`: `type=semver,pattern={{version}}`, `type=semver,pattern={{major}}.{{minor}}`, `type=sha,format=short`, `type=raw,value=latest,enable={{is_default_branch}}`                                                          |
| 6   | `docker/build-push-action@v6` (`id: build`) | `context: .`, `file: ${{ matrix.dockerfile }}`, `push: true`, `tags/labels` da step 5, `cache-from: type=gha`, `cache-to: type=gha,mode=max`, **`provenance: false`** (la provenienza è attestata esplicitamente)                                                                   |
| 7   | `anchore/sbom-action@v0`                    | `image: ${{ env.IMAGE_BASE }}/${{ matrix.service }}@${{ steps.build.outputs.digest }}`, `format: cyclonedx-json`, `output-file: sbom-${{ matrix.service }}.cdx.json`, `upload-artifact: false`                                                                                      |
| 8   | `actions/upload-artifact@v4`                | `name: sbom-${{ matrix.service }}`, path dello step 7, `retention-days: 30` (**AC-10**)                                                                                                                                                                                             |
| 9   | `sigstore/cosign-installer@v3`              | —                                                                                                                                                                                                                                                                                   |
| 10  | `cosign sign --yes <image>@<digest>`        | keyless, nessuna chiave nel repo (**AC-9**, ADR-0003)                                                                                                                                                                                                                               |
| 11  | `actions/attest-build-provenance@v1`        | `subject-name`, `subject-digest: ${{ steps.build.outputs.digest }}`                                                                                                                                                                                                                 |

Matrix: `core-app` → `infra/docker/core-app.Dockerfile`; `document-service`; `bff`; `frontend`.

Job aggiuntivo `sbom-platform`: `setup-java` + `./mvnw -B -ntp -pl core-app -am -DskipTests package` **non** necessario; basta `mvn -B cyclonedx:makeAggregateBom` (plugin dichiarato in `pluginManagement`) → `upload-artifact` `sbom-claims-platform` (**AC-10** aggregato Maven).

⚠️ **Pitfall P-3**: senza `provenance: false` su `build-push-action`, BuildKit crea un manifest list con attestazione: il digest restituito è quello della lista e `cosign sign` firma un oggetto diverso da quello che i verificatori si aspettano senza `--recursive`.
⚠️ **Pitfall P-4**: il workflow si attiva **sia** sul push su `main` **sia** sul tag creato da `release.yml` → doppio build. È **accettato** (2 minuti) perché garantisce che il tag semver esista sempre; se si vuole evitare, aggiungere `if: github.ref_type == 'tag' || github.event_name == 'workflow_dispatch'` per i soli tag. Documentare la scelta in `docs/ci.md`.

### 6.6 `release.yml` — AC-13

```yaml
on: { push: { branches: [main] } }
permissions: { contents: write, issues: write, pull-requests: write }
```

- `checkout@v4` con `fetch-depth: 0` (**obbligatorio**: semantic-release legge la storia dei tag).
- `setup-node@v4` (20) → `npx --yes semantic-release` oppure `cycjimmy/semantic-release-action@v4`.
- `env: GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}`.
- Effetti: tag `vX.Y.Z`, aggiornamento `CHANGELOG.md`, GitHub Release. Il **bump del `VERSION` e del `pom.xml` non è automatico** (ADR-0004): se si vuole, è un commit successivo manuale (`docs/versioning.md`).

### 6.7 `pr-title.yml` — Conventional Commits

```yaml
on: { pull_request: { types: [opened, edited, synchronize, reopened] } }
permissions: { pull-requests: read }
```

`amannn/action-semantic-pull-request@v5`, `types: [feat, fix, chore, docs, refactor, test, build, ci, perf]`, `requireScope: false`, `wip: false`.

---

## 7. Contratti API — già consegnati con questo piano

I 3 file sono stati scritti **leggendo i controller reali** (§ elenco file in §0.1) e validati come YAML.

| File                                     | Endpoint documentati                                                                                                                                                                                        | Note di allineamento                                                                                                                                                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `docs/api/core-app.openapi.yaml`         | `GET/POST /api/v1/claims`, `GET /{id}`, `PATCH /{id}/status`, `GET /{id}/status-history`, `POST /{id}/documents`, `GET /api/v1/policies`, `GET /api/v1/policies/{id}`, `GET /actuator/health`               | `sort` default `openedAt,desc` con alias `createdAt`; `size` clampato 1..100; `409` documentato con esempio Problem; `ClaimStatus` a 7 valori; `Problem` = RFC 9457 (`type` default `about:blank`, `instance` = request URI)                          |
| `docs/api/document-service.openapi.yaml` | `POST /api/v1/documents` (**multipart: parte `file`; `claimId`, `documentType` in **query string**), `GET /api/v1/documents?claimId=`, `GET /{id}`, `GET /{id}/content`, `DELETE /{id}`, `/actuator/health` | `413` per upload >20MB; `Content-Disposition: attachment` con filename RFC 5987; sort fisso `uploadedAt,desc`; `claimId` = riferimento logico, nessuna FK                                                                                             |
| `docs/api/bff.openapi.yaml`              | `GET /bff/v1/claims`, `GET /bff/v1/claims/{id}`, `GET /bff/v1/dashboard/summary`, `/actuator/health`                                                                                                        | lista **piatta** (nessun envelope di paginazione); detail **senza** fallback parziale (404→404, altri errori→502, servizio irraggiungibile→503); `totalOpen` = DRAFT+SUBMITTED+UNDER_REVIEW+INTEGRATION_REQUESTED; `byStatus` con tutti e 7 gli stati |

Vincolo per il frontend: `frontend/src/types/claims.ts` deve restare **strutturalmente identico** a `ClaimSummaryResponse`/`ClaimDetailResponse`/`DocumentSummaryResponse`/`DashboardSummaryResponse`.

---

## 8. Database e frontend: nessuna modifica

Vedi §11 (SKIP analysis). Le migrazioni Flyway esistono e sono già coperte da `docs/db/README.md`; questo piano **non** aggiunge colonne, tabelle o indici.

---

## 9. Matrice di verifica (comandi eseguibili in questo ambiente)

Tutti i comandi si eseguono da `WORKSPACE ROOT`. **Nessuno richiede Docker né rete** (tranne il primo `npm ci` / prima risoluzione Maven).

### 9.1 Backend (non deve regredire)

```bash
mvn -B -ntp verify                       # usa mvn: ./mvnw scarica la distro 3.9.9
# atteso: BUILD SUCCESS, spotless:check + checkstyle:check verdi su 3 moduli
```

### 9.2 Frontend

```bash
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:run        # NON "run test -- --run"
npm --prefix frontend run build
```

### 9.3 Igiene root (equivalente locale dei job CI)

```bash
# Prettier unico (ADR-0008)
./frontend/node_modules/.bin/prettier --check .
```

⚠️ **Pitfall P-5 — stato di partenza reale (verificato)**: oggi questo comando segnala **5 file già presenti** nel repo, tutti documentazione scritta prima dell'introduzione di Prettier:
`bff/README.md`, `core-app/README.md`, `docs/db/README.md`, `docs/plan/TK-DE445B-blueprint.md`, `document-service/README.md`.
Sono **scarti di sola formattazione**: l'agente di Wave A deve eseguire **una volta** `./frontend/node_modules/.bin/prettier --write` su quei 5 file (nessun cambio di contenuto) e includerli nello stesso commit, altrimenti la DoD "zero warning" è irraggiungibile. **Non** aggiungerli a `.prettierignore`: sarebbero documenti fuori standard.

```bash
# Parsing di tutti gli YAML (offline, js-yaml già in frontend/node_modules - ADR-0009)
node -e "
const y=require('./frontend/node_modules/js-yaml'),fs=require('fs'),cp=require('child_process');
const files=cp.execSync(\"ls .github/workflows/*.yml infra/docker-compose.yml docs/api/*.yaml\").toString().trim().split('\n');
let bad=0; for(const f of files){try{y.load(fs.readFileSync(f,'utf8'));console.log('OK   '+f)}catch(e){bad++;console.log('FAIL '+f+': '+e.message)}}
process.exit(bad?1:0)"

# Gate segreti (deve produrre output vuoto)
grep -rniE '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist

# Script infra: sintassi + bit di esecuzione
for s in infra/scripts/*.sh; do sh -n "$s" && echo "OK $s"; done
ls -l infra/scripts/*.sh        # atteso -rwxr-xr-x
```

### 9.4 Verifica statica dei Dockerfile (docker assente → solo statica)

```bash
for f in infra/docker/*.Dockerfile; do
  echo "== $f"; grep -c '^FROM ' "$f";        # >= 2 (multi-stage)
  grep -n 'USER ' "$f";                        # almeno un USER non-root
  grep -n 'HEALTHCHECK' "$f";                  # presente
  grep -nE '^(EXPOSE|ENTRYPOINT|CMD)' "$f";    # 8180/8181/8185/8188 coerenti
  grep -nE '(PASSWORD|SECRET|TOKEN)=' "$f";    # atteso: nessun match
done
```

### 9.5 Verifiche possibili **solo in CI** (da dichiarare nel report QA)

`docker build` reale · `docker compose up` con PostgreSQL 16 · `cosign verify` keyless · upload SBOM come artefatto · caricamento SARIF · esecuzione CodeQL · semantic-release.

### 9.6 Git

```bash
git status --porcelain          # vuoto a fine lavoro
git ls-files -s mvnw            # 100755 (già corretto, non serve chmod)
git ls-files infra/scripts     # i 3 .sh devono risultare 100755
```

---

## 10. Rischi e mitigazioni (aggiornato)

| #    | Rischio                                                                            | Impatto                                                      | Mitigazione                                                                                                                                                             |
| ---- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R-1  | **Token senza `contents: write`** → push `403`                                     | AC-11 non verificabile end-to-end; nessuna pipeline eseguita | Responsabilità del PO/Release Manager: rigenerare il PAT con `contents:write` (+ `workflow`, `packages:write` se serve). Il codice è pronto: nessun workaround nel repo |
| R-2  | Docker assente in locale                                                           | 4 Dockerfile mai eseguiti prima della CI                     | multi-stage minimali, `# syntax` pinnata, nessun build-arg segreto, verifica statica §9.4, `docker buildx` come prima esecuzione reale in CI                            |
| R-3  | `gitleaks-action` richiede licenza                                                 | AC-7 rosso                                                   | gate grep obbligatorio in `ci.yml` (ADR-0010) + `GITLEAKS_LICENSE` documentato come opzionale                                                                           |
| R-4  | NVD rate-limit su OWASP DC                                                         | job settimanale lento/rosso                                  | `schedule`-only + `NVD_API_KEY` opzionale + Trivy come gate primario (ADR-0006)                                                                                         |
| R-5  | GHCR rifiuta owner/repo con maiuscole o `_`                                        | build rotta al primo push                                    | normalizzazione lowercase in step dedicato; fallback path `test-newera` (ADR-0011); il nome del repo GitHub non si tocca                                                |
| R-6  | Tag delle action inesistenti (`trivy-action@0.28.0`, `attest-build-provenance@v1`) | workflow non parte                                           | prima del merge verificare l'esistenza del tag su GitHub; in caso, usare l'ultima `0.x` disponibile. Dependabot mantiene aggiornato                                     |
| R-7  | `provenance` di default in build-push-action                                       | `cosign verify` fallisce                                     | `provenance: false` + `attest-build-provenance` esplicito (P-3)                                                                                                         |
| R-8  | Doppio trigger main+tag in `container-images.yml`                                  | 2 build identiche                                            | accettato e documentato (P-4)                                                                                                                                           |
| R-9  | Drift contratti OpenAPI ↔ codice                                                   | FE/BFF disallineati                                          | snapshot versionati (ADR-0009) + checklist nel PR template + review obbligatoria CODEOWNERS                                                                             |
| R-10 | PAT in chiaro in `.git/config`                                                     | esposizione credenziale se il workspace viene archiviato     | P-1/P-2/P-3 (§0.4): non copiare mai l'URL con credenziali in file/report                                                                                                |
| R-11 | `permissions` troppo ampi nei workflow                                             | superficie d'attacco                                         | permessi minimi in testa + override per job (§6)                                                                                                                        |

---

## 11. Ordine di esecuzione e parallelizzazione

1. **Wave A-remainder** (E-A-1..E-A-23) — **bloccante**, un solo agente. I workflow referenziano `VERSION`, `.gitleaks.toml`, `.prettierrc.json`, `docs/api/*`: senza questi file la CI è rotta.
2. **Wave E-INF** (E-INF-1..17, incluso `.dockerignore` **prima** di tutto: senza, il context di build include `node_modules` e `target`).
3. **Wave E-CI** (E-CI-1..7) — dopo E-INF, perché `container-images.yml` cita i path dei Dockerfile.
4. **Verifica** (§9) — QA agent, con report esplicito di ciò che è verificabile solo in CI.
5. **Commit/push** — Release Manager, con messaggio `feat(TK-DE445B): add CI/CD pipeline, container images and repo governance`. Se il push resta `403`, il commit locale è comunque valido e va conservato.

---

## 12. SKIP analysis (domini non impattati da questo piano)

**FRONTEND_SKIP: nessuna modifica al codice frontend.** `frontend/**` è completo e non va toccato: questo piano aggiunge solo `infra/docker/frontend.Dockerfile` + `infra/docker/nginx/default.conf` (packaging, non UI). Nessuna nuova pagina, rotta, componente, tipo o test TS. L'unico vincolo è che `frontend/src/types/claims.ts` resti allineato a `docs/api/bff.openapi.yaml` (nessuna azione richiesta: già allineato).

**DB_SKIP: nessuna modifica a schema, migrazioni o query.** `V1__init_claims.sql`, `V1__init_documents.sql` e `infra/db/init/01-init-schemas.sql` sono già presenti e corretti; `infra/docker-compose.yml` si limita a **montare** `./db/init` come init script e a creare il database `claims` via `POSTGRES_DB`. Nessuna nuova migrazione Flyway, nessun seed, nessun cambio di colonna.

**BACKEND_SKIP: nessuna modifica ai 3 servizi Java.** Zero file `.java` e zero `application*.yml` vengono toccati: `mvn -B -ntp verify` deve restare verde identico. Le sole aggiunte che riguardano il backend sono **di packaging** (`infra/docker/*.Dockerfile`) e **di contratto** (`docs/api/*.openapi.yaml`, documentazione). Se un agente tocca un file Java per far passare un job CI, è un errore di approccio: il job va corretto, non il codice.
