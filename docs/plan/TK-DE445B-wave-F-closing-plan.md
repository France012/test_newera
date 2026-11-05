# TK-DE445B — Wave F: correzione pipeline e chiusura della commessa

**Stato**: approvato per implementazione — vincolante per gli agenti di sviluppo
**Autore**: Architect / Planner (SOLE PLANNER di questa iterazione)
**Data**: iterazione successiva a `93ffeb7` (`[SDLC-CHECKPOINT] pre-TK-DE445B`)
**Workspace root**: `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`
**Documenti base** (restano fonte di verità, **non vengono sostituiti**):

- `docs/plan/TK-DE445B-blueprint.md` — architettura, API, DB, frontend (Wave 0–F)
- `docs/plan/TK-DE445B-wave-AE-plan.md` — governance root, infra container, CI/CD (Wave A-remainder + E)
- `docs/ci.md`, `docs/versioning.md`, `docs/architecture/adr/0001..0012`

Questo piano **non riapre** nessuna decisione già presa. Copre solo ciò che la verifica eseguita in
questa sessione ha dimostrato essere **rotto o fragile** nella pipeline, più l'evidenza di consegna.

---

## Sintesi per il PO (leggere solo questo, se si ha fretta)

Il repository è **completo**: 202 file tracciati, albero pulito, 5 moduli, build e test **verdi in
locale** (evidenze in §0.2). Non manca nessuna delle voci d'ambito del ticket.

La verifica ha però trovato **3 difetti reali** nella CI, invisibili finché la pipeline non gira su
GitHub, e **1 fragilità** di dipendenza. Due difetti bloccano altrettanti criteri di accettazione:

| ID      | Difetto                                                                                           | AC impattato       | Gravità        |
| ------- | ------------------------------------------------------------------------------------------------- | ------------------ | -------------- |
| **D-1** | `aquasecurity/trivy-action@0.28.0` — il ref **non esiste** upstream (il tag è `v0.28.0`)          | AC-5 (SCA)         | bloccante      |
| **D-2** | `.releaserc.json` include `@semantic-release/npm` ma non esiste `package.json` di root → `ENOPKG` | AC-13 (release)    | bloccante      |
| **D-3** | `dependency-check/Dependency-Check_Action@main` — ref mutabile, viola la policy di pinning        | AC-5, supply chain | da correggere  |
| **D-4** | il gate YAML di `ci.yml` usa `js-yaml` come dipendenza **fantasma** (transitiva di `eslint`)      | AC-4 (hygiene)     | da irrobustire |

La correzione sono **4 modifiche puntuali** (2 righe di workflow, 1 riga di `.releaserc.json`, 1
devDependency) + 2 documenti. **Zero righe di codice applicativo** Java/TS, zero migrazioni, zero
modifiche UI.

**Blocco organizzativo noto, fuori dal perimetro tecnico**: il PAT fornito ha sola lettura → `git push`
risponde `403 Permission to France012/test_newera.git denied`. Finché il token non ottiene
`contents: write`, AC-9 (immagini firmate) e AC-11 (`main` builda ad ogni push) restano
**non verificabili end-to-end**, per quanto il codice sia pronto. Nessun workaround è possibile dentro
il repo: serve un PAT rigenerato dal PO.

---

## 0. Current State Report (verificato in questa sessione)

### 0.1 Struttura — 202 file tracciati, worktree pulito

| Area               | Contenuto                                                                                                                                                                                                                          |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Root build         | `pom.xml` (aggregatore `claims-platform:0.1.0`, parent Spring Boot `3.3.5`, Java 21, 3 `<module>`), `mvnw`/`mvnw.cmd`, `.mvn/**`, `config/checkstyle/checkstyle.xml`                                                               |
| `core-app`         | 51 file — esagonale: 5 modelli, 3 port, 2 service, 8 DTO, `ClaimController` (5 endpoint), `PolicyController` (2), 9 classi di persistenza, `V1__init_claims.sql`, 3 test                                                           |
| `document-service` | 26 file — `DocumentController` (5 endpoint), `LocalFileSystemStorage`, `V1__init_documents.sql`, 3 test                                                                                                                            |
| `bff`              | 21 file — `BffClaimController` (2), `DashboardController` (1), 2 client, 4 DTO, `SecurityConfig`/`CorsConfig`/`RestClientConfig`/`OpenApiConfig`, 2 test                                                                           |
| `frontend`         | 33 file — React 18.3 + TS 5.5 + Vite 5.4 + Vitest 2 + ESLint 9 flat + Prettier 3, 3 pagine, 6 componenti, 3 test                                                                                                                   |
| `infra`            | 17 file — 4 Dockerfile multi-stage, `nginx/default.conf`, `docker-compose.yml`, 3 script (755), Kustomize base (6 file), `db/init`                                                                                                 |
| `.github`          | 13 file — 7 workflow, `CODEOWNERS`, `PULL_REQUEST_TEMPLATE.md`, 3 issue template, `dependabot.yml`                                                                                                                                 |
| `docs`             | 21 file — blueprint, piano Wave A-E, 3 snapshot OpenAPI, 12 ADR, `docs/ci.md`, `docs/versioning.md`, `docs/db/README.md`                                                                                                           |
| Governance root    | `README`, `CONTRIBUTING`, `SECURITY`, `CHANGELOG`, `VERSION` (0.1.0), `.editorconfig`, `.gitattributes`, `.gitignore`, `.dockerignore`, `.env.example`, `.gitleaks.toml`, `.releaserc.json`, `.prettierrc.json`, `.prettierignore` |

### 0.2 Comandi eseguiti in questa sessione e loro esito reale

| #   | Comando                                                                                      | Esito verificato                                                                                                                                                                                      |
| --- | -------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `./mvnw -B -ntp verify`                                                                      | **BUILD SUCCESS** in 30.7 s — `claims-platform`, `core-app`, `document-service`, `bff` tutti SUCCESS; Spotless 18 file clean, Checkstyle **0 violazioni**, JaCoCo report generato, surefire 0 failure |
| 2   | `npm --prefix frontend run lint`                                                             | exit 0, `eslint . --max-warnings 0`                                                                                                                                                                   |
| 3   | `npm --prefix frontend run format:check`                                                     | "All matched files use Prettier code style!"                                                                                                                                                          |
| 4   | `npm --prefix frontend run test:run`                                                         | **3 file / 18 test passati** (4.0 s)                                                                                                                                                                  |
| 5   | `npm --prefix frontend run build`                                                            | `tsc --noEmit` ×2 + Vite: 48 moduli, `dist/assets/index-*.js` 231 kB (gzip 74.6 kB)                                                                                                                   |
| 6   | `prettier --check .` (root, binario da `frontend/node_modules`)                              | clean                                                                                                                                                                                                 |
| 7   | gate YAML su `.github` + `infra` + `docs/api` (js-yaml `loadAll`)                            | **22 file / 0 failure** (workflow, template, compose, K8s multi-doc, 3 OpenAPI)                                                                                                                       |
| 8   | gate segreti (`ghp_`, `github_pat_`, `AKIA`, `BEGIN … PRIVATE KEY`)                          | **nessun match**                                                                                                                                                                                      |
| 9   | `VERSION` vs `<version>` del pom root                                                        | `0.1.0` == `0.1.0`                                                                                                                                                                                    |
| 10  | `bash -n` sui 3 script `infra/scripts/*.sh` + `stat -c %a`                                   | sintassi OK, mode **755** su tutti                                                                                                                                                                    |
| 11  | ispezione statica Dockerfile                                                                 | 4 file, **2 stage** ciascuno, `USER 10001:10001` (frontend `USER 101`), `HEALTHCHECK` presente, `EXPOSE` 8180/8181/8185/8188                                                                          |
| 12  | coerenza porte                                                                               | `application.yml` 8180/8181/8185 == `EXPOSE`; nginx `listen 8188` == `EXPOSE` frontend; `proxy_pass http://bff:8185`                                                                                  |
| 13  | `./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom`                 | **BOM CycloneDX 1.5, 95 componenti**, scritto _e validato_ in `target/sbom-claims-platform.json` (glob di upload CI corretto)                                                                         |
| 14  | audit dei 19 `uses:` presenti nei workflow                                                   | 17 ref validi; **D-1** e **D-3** invalidi/fragili (dettaglio §1)                                                                                                                                      |
| 15  | esecuzione diretta di `@semantic-release/npm@11.0.3` `verifyConditions` senza `package.json` | **fallisce**: `ENOPKG: Missing 'package.json' file.` (dettaglio §1, D-2)                                                                                                                              |
| 16  | `npm ls js-yaml`                                                                             | `js-yaml@4.3.2` solo via `eslint@9.39.5 → @eslint/eslintrc@3.3.7` (**D-4**)                                                                                                                           |

### 0.3 Cosa **non** è verificabile in questo ambiente (e va provato in CI)

Toolchain disponibile: **Java 21.0.11**, **Maven 3.9.9** (wrapper), **Node 20.20.2 / npm 10.8.2**,
rete verso Maven Central, npmjs e `api.github.com`.

**Assenti**: `docker`, `docker-compose`, `cosign`, `syft`, `trivy`, `hadolint`, `actionlint`. Quindi
restano dimostrabili **solo in CI**: `docker build` delle 4 immagini, `docker compose up`, `cosign
sign/verify` keyless, SBOM per immagine con syft, upload SARIF/CodeQL, semantic-release. I Dockerfile
sono stati validati solo staticamente (§0.2 riga 11-12).

### 0.4 Igiene del repository

`git status --short` è **vuoto**: `target/`, `dist/`, `node_modules/`, `*.sarif`, `**/sbom-*.json`
sono correttamente ignorati. Il PAT resta confinato in `.git/config` (non tracciato): AC-12 sul
contenuto del repo è rispettato. **Regola invariata (P-1/P-2)**: non copiare mai l'URL con credenziali
in file, report o issue; nei documenti usare `https://github.com/France012/test_newera.git`.

---

## 1. Difetti trovati — prove e correzione

### D-1 (bloccante, AC-5) — `trivy-action` puntato a un ref inesistente

`.github/workflows/dependency-scan.yml:33` → `uses: aquasecurity/trivy-action@0.28.0`.

Prova: `GET /repos/aquasecurity/trivy-action/git/ref/tags/0.28.0` → **404**;
`git ls-remote --tags` restituisce **solo** `refs/tags/v0.28.0` (e nessun branch `0.28.0`).
Conseguenza: il job `trivy-fs` non riesce nemmeno a scaricare l'action → **AC-5 (SCA) rosso** e nessun
SARIF caricato. Il resto dello workflow è corretto (`security-events: write`, `exit-code: '0'` report-only
come da ADR-0006).

**Correzione**: `aquasecurity/trivy-action@v0.28.0` (stessa versione intesa, ref valido). Un eventuale
bump a `v0.36.0` è lasciato a Dependabot, non a questa wave.

### D-2 (bloccante, AC-13) — `semantic-release` non può girare senza `package.json`

`.releaserc.json:24` → `["@semantic-release/npm", { "npmPublish": false }]`.

L'ADR-0008 vieta un `package.json` di root (una sola installazione Prettier, in `frontend/`).
`@semantic-release/npm` chiama **incondizionatamente** `getPkg()` in `verifyConditions`
(`index.js:29`), che con `readPackage` in errore `ENOENT` lancia `ENOPKG`.

Prova eseguita (non dedotta): esecuzione diretta di `verifyConditions({npmPublish:false}, {cwd: <dir
senza package.json>})` con `@semantic-release/npm@11.0.3` →
`SemanticReleaseError: Missing 'package.json' file.` / `ENOPKG`.

Conseguenza: al primo push su `main`, `release.yml` **fallisce in `verifyConditions`** → nessun tag,
nessuna GitHub Release, nessun CHANGELOG (AC-13 non chiuso). Il plugin non serve a nulla qui: non si
pubblica su npm.

**Correzione**: **eliminare** la riga 24 di `.releaserc.json`. Restano `commit-analyzer`,
`release-notes-generator`, `changelog`, `github` (asset `CHANGELOG.md`), `git` (commit del CHANGELOG).
`persist-credentials: false` nel checkout **non** è un problema: `@semantic-release/git` autentica il
push con `GITHUB_TOKEN`.
**Alternativa rifiutata**: aggiungere un `package.json` di root — violerebbe ADR-0008 e la regola
"una sola installazione Prettier".
Tracciamento: nuovo **ADR-0013** (§3.2).

### D-3 (supply chain, AC-5) — action pinnata a un branch mutabile

`.github/workflows/dependency-scan.yml:117` → `uses: dependency-check/Dependency-Check_Action@main`.
Contraddice la convenzione dichiarata in `docs/ci.md` ("actions pinned to a major tag"). Un branch
mutabile esegue codice arbitrario nel runner con `security-events: write`.

Prova: i tag esistenti sono `1.0.0` e `1.1.0` (commit `1.1.0` = `75ba02d6183445fe0761d26e836bde58b1560600`).

**Correzione**: `dependency-check/Dependency-Check_Action@1.1.0`.

### D-4 (fragilità, AC-4) — `js-yaml` come dipendenza fantasma

`ci.yml` (job `repo-hygiene`, step "Validate YAML/OpenAPI snapshots") esegue
`require('./frontend/node_modules/js-yaml')`. Il pacchetto oggi esiste solo perché `eslint` dipende da
`@eslint/eslintrc`, che dipende da `js-yaml@4.3.2`. Se `eslint` cambia albero delle dipendenze, il
job si rompe senza alcuna modifica al repo.

**Correzione**: dichiarare `"js-yaml": "^4.3.2"` nei `devDependencies` di `frontend/package.json` e
rigenerare `frontend/package-lock.json` (`npm install --package-lock-only`).

---

## 2. Requisiti: criteri di accettazione e Definition of Done

### 2.1 Matrice AC (numerazione ereditata dal piano Wave A-E)

| AC    | Requisito                             | Stato dopo questa wave                                                                   | Evidenza                                                   |
| ----- | ------------------------------------- | ---------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| AC-1  | 5 moduli separati                     | **MET**                                                                                  | §0.1                                                       |
| AC-2  | Build Java 21 + lint/format condivisi | **MET**, verificato localmente                                                           | §0.2 riga 1                                                |
| AC-3  | Lint/format/typecheck frontend        | **MET**, verificato localmente                                                           | §0.2 righe 2-5                                             |
| AC-4  | CI: build + unit test + lint          | **MET** a file; dopo D-4 **robusto**; prova finale su `main`                             | `.github/workflows/ci.yml`, §0.2 righe 1-7                 |
| AC-5  | SCA                                   | **NON MET** oggi per D-1; **MET** dopo la correzione F-1 + F-3                           | `dependency-scan.yml`                                      |
| AC-6  | SAST                                  | **MET** a file (CodeQL `java-kotlin` manual + `javascript-typescript` none), prova in CI | `codeql.yml`                                               |
| AC-7  | Secret scanning                       | **MET** (gitleaks full history + gate `grep` sempre attivo)                              | `secret-scan.yml`, §0.2 riga 8                             |
| AC-8  | Docker multi-stage per servizio       | **MET** staticamente; build reale solo in CI                                             | §0.2 righe 11-12                                           |
| AC-9  | Immagini prodotte e firmate in CI     | **PRONTO**, eseguibile solo dopo lo sblocco del PAT                                      | `container-images.yml`                                     |
| AC-10 | SBOM CycloneDX come artefatto         | **MET** per l'aggregato (riprodotto in locale); per-immagine solo in CI                  | §0.2 riga 13                                               |
| AC-11 | `main` builda e testa ad ogni push    | **PRONTO**, bloccato dal `403`                                                           | §5, §7                                                     |
| AC-12 | Nessun segreto in chiaro              | **MET**                                                                                  | §0.2 riga 8, §0.4                                          |
| AC-13 | PR template, CODEOWNERS, versioning   | **MET** a file; **release riparato** da D-2                                              | `.github/**`, `.releaserc.json`, `VERSION`, `CHANGELOG.md` |

### 2.2 Definition of Done di questa wave

1. Le 4 correzioni di §3.1 sono applicate **esattamente** come specificato (nessun'altra riga toccata).
2. `./mvnw -B -ntp verify` resta **BUILD SUCCESS** (nessun file Java modificato).
3. `npm --prefix frontend ci` + `lint` + `format:check` + `test:run` + `build` restano verdi
   (`package-lock.json` aggiornato insieme a `package.json`).
4. `prettier --check .` verde, incluso il nuovo documento `docs/plan/TK-DE445B-wave-F-closing-plan.md`
   e i documenti toccati.
5. Gate YAML verde su `.github`, `infra`, `docs/api`.
6. **Nessun** `uses:` resta su un branch mutabile: `grep -rn "@main\|@master" .github/workflows/` → vuoto.
7. Tutti i 19 ref `uses:` risolvono upstream (script di verifica in §5, riga 5).
8. `docs/qa/TK-DE445B-local-verification.md` compilato con gli output reali (§3.2).
9. `git status` pulito dopo il commit; messaggio `fix(ci): repair SCA and release pipelines (TK-DE445B)`.

---

## 3. Architettura della modifica

### 3.1 File da MODIFICARE (percorsi esatti, dentro WORKSPACE ROOT)

| #   | File                                    | Riga                          | Prima                                                 | Dopo                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| --- | --------------------------------------- | ----------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-1 | `.github/workflows/dependency-scan.yml` | 33                            | `uses: aquasecurity/trivy-action@0.28.0`              | `uses: aquasecurity/trivy-action@v0.28.0`                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| F-2 | `.github/workflows/dependency-scan.yml` | 117                           | `uses: dependency-check/Dependency-Check_Action@main` | `uses: dependency-check/Dependency-Check_Action@1.1.0`                                                                                                                                                                                                                                                                                                                                                                                                                        |
| F-3 | `.releaserc.json`                       | 24                            | `["@semantic-release/npm", { "npmPublish": false }],` | _riga rimossa_                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| F-4 | `frontend/package.json`                 | `devDependencies`             | _(assente)_                                           | `"js-yaml": "^4.3.2",` inserito in ordine alfabetico (prima di `jsdom`)                                                                                                                                                                                                                                                                                                                                                                                                       |
| F-5 | `frontend/package-lock.json`            | —                             | —                                                     | rigenerato: `npm install --package-lock-only` (nient'altro deve cambiare se non il nodo `js-yaml`)                                                                                                                                                                                                                                                                                                                                                                            |
| F-6 | `docs/ci.md`                            | sezione "Workflow map" / note | —                                                     | (a) action pinnate a **tag di release** (`trivy-action@v0.28.0`, `Dependency-Check_Action@1.1.0`), nessun `@main`; (b) nuova sezione **Branch protection** con i check richiesti: `ci-success`, `dependency-scan`, `CodeQL (java-kotlin)`, `CodeQL (javascript-typescript)`, `gitleaks`, `PR title`, più GitHub secret scanning + push protection da attivare nelle impostazioni del repo; (c) nota che `release.yml` **non** usa `@semantic-release/npm` (ADR-0008/ADR-0013) |
| F-7 | `docs/architecture/README.md`           | tabella "Decisions"           | ultima riga `0012`                                    | aggiungere riga `0013 — semantic-release without the npm plugin`                                                                                                                                                                                                                                                                                                                                                                                                              |

**Dettaglio F-3** (JSON deve restare valido — verificare con `node -e "require('./.releaserc.json')"`):

```json
["@semantic-release/release-notes-generator", { "preset": "conventionalcommits" }],
["@semantic-release/changelog", { "changelogFile": "CHANGELOG.md" }],
["@semantic-release/github", { "assets": ["CHANGELOG.md"] }],
```

**Dettaglio F-1**: non toccare `exit-code: '0'`, `severity`, `ignore-unfixed`, `format: sarif`,
`output: trivy-results.sarif` (sono corretti e coerenti con ADR-0006 e con l'upload SARIF).

### 3.2 File da CREARE

| #   | Percorso                                                            | Contenuto richiesto                                                                                                                                                                                                                                                                                                                                                                                     | Chi       |
| --- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| C-1 | `docs/architecture/adr/0013-semantic-release-without-npm-plugin.md` | ADR in italiano, formato identico agli altri (Status: **accepted**; Context: ADR-0008 vieta `package.json` di root; Decision: nessun plugin npm in `.releaserc.json`; Consequences: nessun publish npm, `CHANGELOG.md` versionato via `@semantic-release/git`, tag `vX.Y.Z` invariati; Alternative scartate: `package.json` root, `pkgRoot: frontend`). Deve citare l'errore `ENOPKG` come motivazione. | Developer |
| C-2 | `docs/qa/TK-DE445B-local-verification.md`                           | Registro di verifica: tabella comando → esito (§0.2 righe 1-13), sezione "Solo in CI" (docker, cosign, syft, trivy, CodeQL, semantic-release), sezione "Blocco noto: push 403". **Nessun output deve contenere il PAT.**                                                                                                                                                                                | QA        |

### 3.3 File che NON vanno toccati

- Tutti i `**/*.java` (51 + 26 + 21 file) e ogni `application*.yml` → `mvn verify` deve restare
  identico (§2.2 punto 2).
- Tutti i `frontend/src/**` (pagine, componenti, `types/claims.ts`, test), `vite.config.ts`,
  `eslint.config.js`, `tsconfig*.json` → nessuna modifica UI/TS.
- `docs/api/*.openapi.yaml` → contratti invariati.
- `core-app/src/main/resources/db/migration/**`, `document-service/src/main/resources/db/migration/**`,
  `infra/db/init/**` → nessuna migrazione.
- `infra/docker/**`, `infra/k8s/**`, `infra/docker-compose.yml`, `infra/scripts/**` → già corretti
  (§0.2 righe 10-12).
- `pom.xml`, `config/checkstyle/checkstyle.xml`, `.prettierrc.json`, `.prettierignore`, `.gitleaks.toml`,
  `CODEOWNERS`, `PULL_REQUEST_TEMPLATE.md`, `dependabot.yml` → invariati.

### 3.4 Contratti API — nessuna modifica

Gli snapshot `docs/api/{core-app,document-service,bff}.openapi.yaml` restano la fonte di verità:
`core-app` `/api/v1/claims` (5) + `/api/v1/policies` (2) su :8180, `document-service`
`/api/v1/documents` (5) su :8181, `bff` `/bff/v1/claims` (2) + `/bff/v1/dashboard` (1) su :8185.
Questa wave **non** aggiunge, rimuove o cambia endpoint: nessun impatto sul frontend né sui test.

### 3.5 Database — nessuna modifica

Nessuna tabella, colonna, indice o migrazione Flyway. Lo schema resta `coreapp` (core-app) e
`documents` (document-service), `ddl-auto: validate`, Flyway forward-only.

### 3.6 UI — nessuna modifica

Nessuna pagina, rotta, componente, form, tipo o test TS.

---

## 4. Opzionali (NON implementare senza approvazione esplicita del PO)

| ID   | Proposta                                                                                                                                                                      | Perché è opzionale                                                            |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| F-O1 | Job **build-only** (niente push, niente firma) su PR che toccano `infra/docker/**`, per eseguire i Dockerfile prima del merge (mitiga R-2 di Wave E: Dockerfile mai eseguiti) | AC-8 è già soddisfatto; costa ~4 build per PR tocchi infra                    |
| F-O2 | Allegare gli SBOM alla GitHub Release in `release.yml` (oggi sono artefatti con retention 30 giorni)                                                                          | AC-10 è già soddisfatto dagli artefatti; richiede condivisione cross-workflow |
| F-O3 | Bump `trivy-action` a `v0.36.0`, `gitleaks-action` a `v3`, `attest-build-provenance` a `v4`                                                                                   | Rumore di diff in questa wave; Dependabot è configurato per farlo             |

---

## 5. Matrice di verifica (comandi eseguibili in questo ambiente)

```bash
# 1. Backend invariato: BUILD SUCCESS atteso, 3 moduli + aggregatore
./mvnw -B -ntp verify

# 2. Frontend invariato (dopo F-4/F-5): lint + format + 18 test + build
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:run
npm --prefix frontend run build

# 3. Formattazione repository (il nuovo documento deve essere già formattato)
./frontend/node_modules/.bin/prettier --check .

# 4. Gate YAML (22 file / 0 failure attesi)
node -e "const y=require('./frontend/node_modules/js-yaml'),fs=require('fs'),p=require('path');const w=d=>fs.readdirSync(d,{withFileTypes:true}).forEach(e=>{const f=p.join(d,e.name);e.isDirectory()?w(f):/\.ya?ml$/.test(e.name)&&y.loadAll(fs.readFileSync(f,'utf8'))});['.github','infra','docs/api'].forEach(w);console.log('YAML OK')"

# 5. Nessun ref mutabile + esistenza reale dei tag usati (F-1/F-2)
grep -rnE "uses:.*@(main|master)$" .github/workflows/ || echo "no mutable ref"
git ls-remote --tags https://github.com/aquasecurity/trivy-action.git | grep -E "refs/tags/v0\.28\.0$"
git ls-remote --tags https://github.com/dependency-check/Dependency-Check_Action.git | grep -E "refs/tags/1\.1\.0$"

# 6. .releaserc.json valido e senza plugin npm
node -e "const c=require('./.releaserc.json');if(JSON.stringify(c).includes('@semantic-release/npm'))process.exit(1);console.log('releaserc OK:',c.plugins.map(p=>Array.isArray(p)?p[0]:p).join(', '))"

# 7. js-yaml dichiarato (F-4)
grep -n '"js-yaml"' frontend/package.json frontend/package-lock.json | head

# 8. Gate segreti (output vuoto atteso)
grep -rniE '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist

# 9. Coerenza versione
test "$(tr -d '[:space:]' < VERSION)" = "$(awk '/<\/parent>/{f=1;next} f && /<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)" && echo "VERSION OK"

# 10. SBOM aggregato riproducibile (AC-10, parte locale)
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom
ls -l target/sbom-claims-platform.json
```

Dopo il push (sbloccato il PAT): verificare su GitHub che i run `ci.yml`, `dependency-scan.yml`,
`codeql.yml`, `secret-scan.yml`, `release.yml`, `container-images.yml` siano **tutti verdi**, e che
`cosign verify --certificate-identity-regexp '^https://github.com/France012/test_newera/' --certificate-oidc-issuer https://token.actions.githubusercontent.com <image>@<digest>`
passi sulle 4 immagini.

---

## 6. Ordine di esecuzione e ownership

1. **Developer (1 agente)** — F-1, F-2, F-3, F-4, F-5, C-1, F-7, poi F-6. Eseguire la matrice §5.
   Un solo commit `fix(ci): repair SCA and release pipelines (TK-DE445B)`.
2. **QA** — rieseguire §5, compilare C-2 riportando output reali e dichiarando esplicitamente ciò che
   è verificabile solo in CI.
3. **Release Manager** — `git push -u origin main` (richiede PAT `contents: write`). Se `403`, **non**
   tentare workaround: riportare al PO e conservare il commit locale.
4. **PO** — rigenerare il PAT con `contents: write` (+ `workflow` se si modificano workflow), attivare
   branch protection su `main` con i check di F-6, poi verificare i run.

---

## 7. Rischi e mitigazioni

| #   | Rischio                                                                           | Impatto                                          | Mitigazione                                                                                                                            |
| --- | --------------------------------------------------------------------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| R-1 | PAT senza `contents: write` → push `403`                                          | AC-9/AC-11 non verificabili; nessun run eseguito | Azione del PO (§6 punto 4). Nessun workaround nel repo. Commit locale valido e conservato                                              |
| R-2 | `trivy-action@v0.28.0` accetta l'input `ignore-unfixed`? (verificato solo il tag) | job rosso per input sconosciuto                  | Gli input usati sono documentati nella 0.28.0; in caso di errore, primo sospetto è la versione → bump a `v0.36.0` (F-O3)               |
| R-3 | `Dependency-Check_Action@1.1.0` accetta gli input `project/path/format/out/args`  | job settimanale rosso                            | Gli input sono stabili dal 1.0.0; il job gira solo su `schedule`/`workflow_dispatch`, non blocca le PR                                 |
| R-4 | `@semantic-release/git` non riesce a pushare con `persist-credentials: false`     | niente commit CHANGELOG                          | Il plugin autentica con `GITHUB_TOKEN`; se fallisse, `persist-credentials: true` è l'unica modifica ammessa (documentarla in ADR-0013) |
| R-5 | Docker assente in locale                                                          | 4 immagini mai eseguite prima della CI           | Verifica statica §0.2; eventuale F-O1 come rete di sicurezza                                                                           |
| R-6 | Deriva formattazione sul nuovo markdown                                           | `ci.yml`/`repo-hygiene` rosso                    | Eseguire `prettier --write docs/plan/TK-DE445B-wave-F-closing-plan.md docs/qa/*.md docs/architecture/**/*.md` prima del commit         |
| R-7 | PAT in chiaro in `.git/config`                                                    | esposizione se il workspace viene archiviato     | P-1/P-2/P-3 invariati: mai copiare l'URL con credenziali in file o report                                                              |

---

## 8. SKIP analysis (domini non impattati)

**FRONTEND_SKIP: nessuna modifica al codice frontend.** Nessuna pagina, rotta, componente, form,
tipo o test TS viene toccato. L'unica modifica sotto `frontend/` è **di build** (`package.json` +
`package-lock.json`, devDependency `js-yaml` per il gate di repo-hygiene) e non altera bundle, runtime
né comportamento UI. `frontend/src/**` resta byte-identico e i 18 test devono restare verdi.

**DB_SKIP: nessuna modifica a schema, migrazioni o query.** `V1__init_claims.sql`,
`V1__init_documents.sql`, `infra/db/init/01-init-schemas.sql` restano invariati; nessuna nuova
migrazione Flyway, nessun seed, nessuna colonna, `SchemaMigrationContractTest` deve restare verde.

**BACKEND_SKIP: nessuna modifica ai 3 servizi Java.** Zero file `.java` e zero `application*.yml`
vengono toccati: `./mvnw -B -ntp verify` deve produrre lo stesso esito registrato in §0.2 riga 1.
Le sole modifiche che sfiorano il backend sono **di pipeline** (`dependency-scan.yml`) e **di
documentazione** (`docs/ci.md`, ADR-0013). Se un job CI è rosso, si corregge il job, **non** il codice.
