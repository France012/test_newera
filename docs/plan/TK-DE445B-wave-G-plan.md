# TK-DE445B — Wave G: chiusura della pipeline (SCA, release, governance docs)

**Stato**: approvato per implementazione — vincolante per gli agenti di sviluppo
**Autore**: Architect / Planner (SOLE PLANNER di questa iterazione)
**Data**: iterazione successiva a `8c9c85a` (già pushato su `origin/main`)
**Workspace root**: `/app/workspace/52fefe5b-ad4d-4db1-b639-a4bb0840deaa`

**Documenti base** (restano fonte di verità, **non vengono sostituiti**):

- `docs/plan/TK-DE445B-blueprint.md` — architettura, API, DB, frontend
- `docs/plan/TK-DE445B-wave-AE-plan.md` — governance root, infra container, CI/CD
- `docs/plan/TK-DE445B-wave-F-closing-plan.md` — diagnosi dei difetti di pipeline (D-1…D-4)
- `docs/ci.md`, `docs/versioning.md`, `docs/architecture/adr/0001..0012`

Questo piano **non riapre** nessuna decisione già presa. Copre **solo** ciò che la verifica di questa
sessione ha dimostrato essere **ancora aperto**: 3 righe di pipeline, 1 file di configurazione, 3
documenti. **Zero** righe di codice applicativo.

---

## Sintesi per il PO (leggere solo questo, se si ha fretta)

Il repository è **completo e pushato** (`8c9c85a` == `origin/main`, worktree pulito, 205 file
tracciati). Le 13 voci d'ambito del ticket sono tutte rappresentate da file reali.

Resta aperto **un solo blocco tecnico**, già diagnosticato in Wave F e **non ancora applicato**,
più **una scoperta nuova** di questa sessione che invalida la correzione proposta allora:

| ID       | Difetto                                                                                                                 | AC impattato        | Gravità     |
| -------- | ----------------------------------------------------------------------------------------------------------------------- | ------------------- | ----------- |
| **D-1**  | `aquasecurity/trivy-action@0.28.0` — ref **inesistente**: i tag upstream sono `v0.28.0` (verificato: SHA `915b19b`)     | AC-5 (SCA)          | bloccante   |
| **D-2**  | `.releaserc.json` include `@semantic-release/npm` ma non esiste `package.json` di root → `ENOPKG` in `verifyConditions` | AC-13 (release)     | bloccante   |
| **D-3**  | `dependency-check/Dependency-Check_Action@main` — ref mutabile                                                          | AC-5, supply chain  | bloccante   |
| **D-3b** | **NUOVO**: il tag `1.1.0` (correzione proposta in Wave F) espone `project/path/format/others` e **ignora** `out`/`args` | AC-5                | bloccante   |
| **D-5**  | `docs/ci.md` senza sezione branch protection; nessun ADR-0013; nessun registro di verifica locale                       | AC-13, auditabilità | documentale |

**Correzione Wave G** = 4 modifiche puntuali + 3 documenti. Nessun file `.java`, `.ts`, `.tsx`, SQL,
OpenAPI, Dockerfile o manifest K8s viene toccato.

**Fuori dal perimetro tecnico**: branch protection e attivazione di GitHub secret scanning/push
protection sono **impostazioni del repository**, non file. Le esegue il PO (§5, punto 4).

---

## 0. Current State Report (verificato in questa sessione)

### 0.1 Revisione

| Voce                    | Valore verificato                                                         |
| ----------------------- | ------------------------------------------------------------------------- |
| HEAD locale             | `8c9c85a` (`[SDLC-CHECKPOINT] pre-TK-DE445B`)                             |
| `origin/main`           | `8c9c85a` — **identico**, push già eseguito (Wave F §6 punto 3 sbloccato) |
| Worktree                | **pulito** (`git status --porcelain` vuoto)                               |
| File tracciati          | 205                                                                       |
| Moduli Maven            | `claims-platform:0.1.0` → `core-app`, `document-service`, `bff`           |
| Frontend                | React 18.3 + TS 5.5 + Vite 5.4 + Vitest 2 + ESLint 9 flat + Prettier 3    |
| `frontend/node_modules` | presente (Prettier/ESLint eseguibili in locale)                           |
| `package.json` root     | **assente** (per ADR-0008) → causa di D-2                                 |

### 0.2 Evidenze raccolte in questa sessione

| #   | Comando / ispezione                                                                         | Esito reale                                                                                                                                         |
| --- | ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `git ls-remote --tags https://github.com/aquasecurity/trivy-action`                         | tag **`v`-prefissati**; `refs/tags/v0.28.0` = `915b19bbe73b…`; **`refs/tags/0.28.0` assente** → **D-1 confermato**                                  |
| 2   | `curl raw.githubusercontent.com/aquasecurity/trivy-action/v0.28.0/action.yaml` + grep input | i 7 input usati esistono: `scan-type`, `scan-ref`, `exit-code`, `ignore-unfixed`, `severity`, `format`, `output` → **R-2 di Wave F chiuso**         |
| 3   | `git ls-remote --tags https://github.com/dependency-check/Dependency-Check_Action`          | esistono **solo** `1.0.0` e `1.1.0`; nessun major tag; `main` HEAD = `1e54355a8b4c8abaa8cc7d0b70aa655a3bb15a6c`                                     |
| 4   | `curl …/Dependency-Check_Action/{1.0.0,1.1.0,main}/action.yml`                              | `1.x`: input `project/path/format/others`, `--out /github/workspace/reports` **hardcoded**; `main`: input `project/path/format/out/args` → **D-3b** |
| 5   | `grep -n '"@semantic-release/npm"' .releaserc.json`                                         | presente alla riga 24 → **D-2 confermato**                                                                                                          |
| 6   | `grep -rnE "uses:.*@(main\|master)$" .github/workflows/`                                    | **1 hit**: `dependency-scan.yml:117` → **D-3 confermato**                                                                                           |
| 7   | audit dei **19** ref `uses:` distinti (41 occorrenze) con `git ls-remote refs/tags/<ref>`   | 19/19 risolvono; `cycjimmy/semantic-release-action@v4` risolve via `refs/heads/v4` (= `v4.2.2`), convenzione major-tag ammessa                      |
| 8   | grep segreti su worktree (`ghp_`, `github_pat_`, `AKIA`, `-----BEGIN … PRIVATE KEY-----`)   | **0 match** → AC-12 verde                                                                                                                           |
| 9   | `ls package.json`, `ls docs/qa`, `grep 0013 docs/architecture/README.md`                    | assenti → **D-5**                                                                                                                                   |
| 10  | `git log --oneline -6`                                                                      | 5 commit, messaggi convenzionali, nessun commit di rumore                                                                                           |

### 0.3 Cosa **non** è verificabile in questo ambiente (resta a carico della CI)

Assenza di **Docker, cosign, syft, trivy, gh CLI, rete verso GitHub Actions**. Quindi: build delle 4
immagini, `cosign sign`/`verify`, SBOM per-immagine, upload SARIF, CodeQL, semantic-release, e
l'esecuzione reale dei workflow restano **dimostrabili solo su GitHub** dopo il push.

---

## 1. Requisiti — matrice di accettazione

| AC    | Requisito                                          | Stato **oggi**                                                        | Chiuso da               |
| ----- | -------------------------------------------------- | --------------------------------------------------------------------- | ----------------------- |
| AC-1  | Monorepo con 5 moduli separati                     | **MET**                                                               | —                       |
| AC-2  | Build Java 21 + Spring Boot, lint/format condivisi | **MET** (root `pom.xml`: Spotless + Checkstyle in `verify`)           | —                       |
| AC-3  | Lint/format/typecheck frontend                     | **MET** (`frontend/package.json` scripts)                             | —                       |
| AC-4  | CI: build + unit test + lint                       | **MET** a file (`ci.yml`, 4 job)                                      | —                       |
| AC-5  | Dependency scan (SCA)                              | **NON MET**: D-1 (tag inesistente) + D-3/D-3b (ref mutabile)          | **G-1, G-2**            |
| AC-6  | SAST                                               | **MET** a file (`codeql.yml`, 2 linguaggi, `build-mode` espliciti)    | —                       |
| AC-7  | Secret scanning                                    | **MET** (`secret-scan.yml` + gate `grep` in `ci.yml`)                 | —                       |
| AC-8  | Docker multi-stage per servizio                    | **MET** staticamente (4 Dockerfile, 2 stage, non-root, `HEALTHCHECK`) | —                       |
| AC-9  | Immagini prodotte e firmate in CI                  | **PRONTO** (`container-images.yml`: cosign keyless + attestation)     | verifica post-push (PO) |
| AC-10 | SBOM CycloneDX come artefatto                      | **MET** a file (5 artefatti: 4 per-immagine + aggregato)              | —                       |
| AC-11 | `main` builda e testa ad ogni push                 | **PRONTO** (`ci.yml` su `push: main`)                                 | verifica post-push (PO) |
| AC-12 | Nessun segreto in chiaro                           | **MET** (§0.2 riga 8)                                                 | —                       |
| AC-13 | PR template, CODEOWNERS, versioning                | **MET** a file; **release riparato** da D-2; documentazione mancante  | **G-3, G-4, G-5, G-6**  |

---

## 2. Definition of Done (Wave G)

1. `grep -rnE "uses:.*@(main|master)$" .github/workflows/` → **vuoto**.
2. `git ls-remote --tags https://github.com/aquasecurity/trivy-action | grep 'refs/tags/v0.28.0$'` → 1 riga.
3. `git ls-remote https://github.com/dependency-check/Dependency-Check_Action | grep 1e54355a` → SHA presente.
4. `node -e "require('./.releaserc.json')"` → OK **e** nessuna occorrenza di `@semantic-release/npm`.
5. `./mvnw -B -ntp verify` → **BUILD SUCCESS** (nessun file Java toccato: stesso esito di Wave F §0.2 riga 1).
6. `npm --prefix frontend run lint` / `format:check` / `test:run` / `build` → verdi (18 test).
7. `./frontend/node_modules/.bin/prettier --check .` → verde, **incluso questo documento**.
8. Gate YAML (`.github`, `infra`, `docs/api`) → 0 failure.
9. `docs/architecture/adr/0013-*.md` presente e linkato in `docs/architecture/README.md`.
10. `docs/qa/TK-DE445B-local-verification.md` compilato con output reali, **senza** alcun token.
11. Un solo commit: `fix(ci): repair SCA and release pipelines (TK-DE445B)`; worktree pulito dopo.

---

## 3. Architettura della modifica

### 3.1 File da MODIFICARE (percorsi esatti, dentro WORKSPACE ROOT)

| #        | File                                    | Riga                | Prima (esatto)                                                | Dopo (esatto)                                                                                                                                                                                                                                                                   |
| -------- | --------------------------------------- | ------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **G-1**  | `.github/workflows/dependency-scan.yml` | 33                  | `        uses: aquasecurity/trivy-action@0.28.0`              | `        # Released tag only: the un-prefixed 0.28.0 does not exist upstream.` + nuova riga `        uses: aquasecurity/trivy-action@v0.28.0`                                                                                                                                   |
| **G-2**  | `.github/workflows/dependency-scan.yml` | 117                 | `        uses: dependency-check/Dependency-Check_Action@main` | `        # Pinned by commit SHA (= upstream main). Tags 1.0.0/1.1.0 expose the older` / `        # input contract (project/path/format/others) and ignore out/args (D-3b).` / `        uses: dependency-check/Dependency-Check_Action@1e54355a8b4c8abaa8cc7d0b70aa655a3bb15a6c` |
| **G-2b** | `.github/workflows/dependency-scan.yml` | 118                 | _(assente)_                                                   | `        continue-on-error: true` immediatamente dopo `uses:` — il job gira **solo** su `schedule`/`workflow_dispatch`, è report-only (ADR-0006) e l'action hardcoda `--noupdate`, che su runner freddo può fallire per DB NVD assente                                          |
| **G-3**  | `.releaserc.json`                       | 24                  | `    ["@semantic-release/npm", { "npmPublish": false }],`     | _riga rimossa_ (JSON deve restare valido; ADR-0013)                                                                                                                                                                                                                             |
| **G-4**  | `docs/ci.md`                            | —                   | vedi dettaglio sotto                                          | vedi dettaglio sotto                                                                                                                                                                                                                                                            |
| **G-6**  | `docs/architecture/README.md`           | tabella _Decisions_ | ultima riga `0012`                                            | nuova riga `0013 — semantic-release without the npm plugin`                                                                                                                                                                                                                     |

**Dettaglio G-1** — non toccare `format: sarif`, `output: trivy-results.sarif`, `severity: CRITICAL,HIGH`,
`ignore-unfixed: true`, `exit-code: '0'`: sono corretti e coerenti con ADR-0006; tutti e 7 gli input
esistono nel tag `v0.28.0` (§0.2 riga 2).

**Dettaglio G-2/G-2b** — il blocco `with:` resta **byte-identico**
(`project: claims-platform`, `path: '.'`, `format: 'HTML'`, `out: 'reports'`,
`args: --failOnCVSS 9 --enableRetired`): sono input validi per `main`, che è la revisione puntata
dallo SHA. **Non** usare `@1.1.0`: accetterebbe solo `others` e ignorerebbe silenziosamente
`out`/`args` (D-3b).

**Dettaglio G-4** — `docs/ci.md`, quattro interventi testuali:

1. In _Common conventions_: sostituire «actions pinned to a major tag and kept current by Dependabot»
   con la policy effettiva: **major tag** (`@v4`) dove esiste e gli input sono stabili; **commit SHA**
   per le action senza tag di release utilizzabile; **mai** `@main`/`@master`; assenza di tag
   `v`-prefisso = ref inesistente (caso `trivy-action`).
2. Nuova sezione **`## Required checks (branch protection)`** con la lista dei check da rendere
   obbligatori su `main`: `ci-success`, `Trivy filesystem scan (pom.xml + package-lock.json)`,
   `npm audit (critical blocking)`, `Analyze (java-kotlin)`, `Analyze (javascript-typescript)`,
   `gitleaks (full history)`, `Validate PR title`; più l'indicazione di attivare nelle **repository
   settings** _Secret scanning_ + _Push protection_ e di richiedere ≥1 review da `CODEOWNERS`.
3. Nota in _Deliberate gaps_: il job OWASP settimanale usa un'action che hardcoda `--noupdate` e
   `/github/workspace/reports`; per questo è `continue-on-error: true` e non partecipa ai check
   obbligatori (coerente con ADR-0006).
4. Nota in _Secrets_ / _release_: `.releaserc.json` **non** usa `@semantic-release/npm` (nessun
   `package.json` di root, ADR-0008 + ADR-0013); `CHANGELOG.md` è versionato da
   `@semantic-release/git`, i tag `vX.Y.Z` sono invariati.

### 3.2 File da CREARE

| #       | Percorso                                                            | Contenuto richiesto                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | Chi       |
| ------- | ------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| **G-5** | `docs/architecture/adr/0013-semantic-release-without-npm-plugin.md` | ADR in italiano, **stesso formato** degli altri 12 (`Status`, `Context`, `Decision`, `Consequences`, `Alternatives`). Status **accepted**. Context: ADR-0008 vieta un `package.json` di root; `@semantic-release/npm` chiama `getPkg()` incondizionatamente in `verifyConditions` → `ENOPKG: Missing 'package.json' file.` Decision: nessun plugin npm; plugin = commit-analyzer, release-notes-generator, changelog, github, git. Consequences: nessun publish npm, `npmPublish` non applicabile, tag/CHANGELOG invariati. Alternatives scartate: `package.json` root (viola ADR-0008), `pkgRoot: frontend` (pubblicherebbe il pacchetto UI). | Developer |
| **G-7** | `docs/qa/TK-DE445B-local-verification.md`                           | Registro di verifica: tabella **comando → esito reale** (§4), sezione «Solo in CI» (docker, cosign, trivy, syft, CodeQL, semantic-release), sezione «Impostazioni repository richieste al PO». **Nessun output deve contenere un token.**                                                                                                                                                                                                                                                                                                                                                                                                      | QA        |

### 3.3 File che NON vanno toccati

- Tutti i `**/*.java` (51 `core-app` + 26 `document-service` + 21 `bff`) e ogni `application*.yml`.
- Tutto `frontend/src/**` (3 pagine, 6 componenti, 3 test, `types/`, `utils/`, `api/`),
  `vite.config.ts`, `eslint.config.js`, `tsconfig*.json`, `package.json`, `package-lock.json`.
- `docs/api/{core-app,document-service,bff}.openapi.yaml` — contratti invariati.
- `core-app/**/db/migration/**`, `document-service/**/db/migration/**`, `infra/db/init/**`.
- `infra/docker/**`, `infra/k8s/**`, `infra/docker-compose.yml`, `infra/scripts/**`.
- `pom.xml`, `config/checkstyle/checkstyle.xml`, `.prettierrc.json`, `.prettierignore`,
  `.gitleaks.toml`, `.editorconfig`, `.gitignore`, `.gitattributes`, `.dockerignore`,
  `.env.example`, `VERSION`, `CHANGELOG.md`, `.github/CODEOWNERS`,
  `.github/PULL_REQUEST_TEMPLATE.md`, `.github/dependabot.yml`, gli altri 6 workflow.
- `.github/workflows/container-images.yml` — cosign keyless, SBOM e attestation sono già corretti.

### 3.4 Contratti API — nessuna modifica

Gli snapshot `docs/api/*.openapi.yaml` restano la fonte di verità: `core-app` `/api/v1/claims` (5) +
`/api/v1/policies` (2) su :8180; `document-service` `/api/v1/documents` (5) su :8181; `bff`
`/bff/v1/claims` (2) + `/bff/v1/dashboard` (1) su :8185. Wave G **non** aggiunge, rimuove o modifica
endpoint.

### 3.5 Database — nessuna modifica

Nessuna tabella, colonna, indice o migrazione Flyway. Schemi `coreapp` / `documents` invariati,
`ddl-auto: validate`, Flyway forward-only.

### 3.6 UI — nessuna modifica

Nessuna pagina, rotta, componente, form, tipo o test TypeScript.

---

## 4. Matrice di verifica (comandi eseguibili in questo ambiente)

```bash
# 1. Ref di action: nessun branch mutabile, tutti i ref risolvono upstream (G-1, G-2)
grep -rnE "uses:.*@(main|master)$" .github/workflows/ || echo "no mutable ref"
git ls-remote --tags https://github.com/aquasecurity/trivy-action | grep -E "refs/tags/v0\.28\.0$"
git ls-remote https://github.com/dependency-check/Dependency-Check_Action | grep 1e54355a

# 2. Release senza plugin npm (G-3)
node -e "const c=require('./.releaserc.json');if(JSON.stringify(c).includes('@semantic-release/npm'))process.exit(1);console.log('releaserc OK:',c.plugins.map(p=>Array.isArray(p)?p[0]:p).join(', '))"

# 3. Backend invariato
./mvnw -B -ntp verify

# 4. Frontend invariato
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:run
npm --prefix frontend run build

# 5. Formattazione repository (i 3 documenti nuovi/modificati devono essere già formattati)
./frontend/node_modules/.bin/prettier --check .

# 6. Gate YAML (workflow, compose, K8s multi-doc, OpenAPI)
node -e "const y=require('./frontend/node_modules/js-yaml'),fs=require('fs'),p=require('path');const w=d=>fs.readdirSync(d,{withFileTypes:true}).forEach(e=>{const f=p.join(d,e.name);e.isDirectory()?w(f):/\.ya?ml$/.test(e.name)&&y.loadAll(fs.readFileSync(f,'utf8'))});['.github','infra','docs/api'].forEach(w);console.log('YAML OK')"

# 7. Gate segreti (output vuoto atteso)
grep -rniE '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist

# 8. Coerenza versione
test "$(tr -d '[:space:]' < VERSION)" = "$(awk '/<\/parent>/{f=1;next} f && /<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)" && echo "VERSION OK"

# 9. SBOM aggregato riproducibile (AC-10, parte locale)
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom && ls -l target/sbom-claims-platform.json
```

**Post-push (solo GitHub)** — per ogni run di `ci.yml`, `dependency-scan.yml`, `codeql.yml`,
`secret-scan.yml`, `release.yml`, `container-images.yml`: esito **verde**; poi su ciascuna delle 4
immagini:

```bash
cosign verify \
  --certificate-identity-regexp '^https://github.com/France012/test_newera/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/france012/test_newera/<service>@<digest>
```

e presenza degli artefatti `sbom-core-app`, `sbom-document-service`, `sbom-bff`, `sbom-frontend`,
`sbom-claims-platform`.

---

## 5. Ordine di esecuzione e ownership

1. **Developer (1 agente)** — applicare **G-1, G-2, G-2b, G-3** (le 4 righe di §3.1), poi **G-5**,
   **G-6**, infine **G-4**. Eseguire `prettier --write` sui 3 file Markdown toccati, poi la matrice §4
   (punti 1-9). Un solo commit `fix(ci): repair SCA and release pipelines (TK-DE445B)`.
2. **QA** — rieseguire §4 integralmente, compilare **G-7** con output reali e dichiarare
   esplicitamente ciò che è verificabile solo in CI.
3. **Release Manager** — `git push -u origin main`. Il push ora è **sbloccato** (§0.1); se tornasse
   `403`, **nessun workaround**: riportare al PO e conservare il commit locale.
4. **PO** — nelle impostazioni del repository: attivare **branch protection** su `main` con i check di
   G-4.2 (§3.2), **secret scanning** + **push protection**, e verificare i run del punto 3 di §4.

---

## 6. Rischi e mitigazioni

| #   | Rischio                                                            | Impatto                                      | Mitigazione                                                                                                                                |
| --- | ------------------------------------------------------------------ | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| R-1 | SHA `1e54355a…` di `main` avanza e il pin resta vecchio            | job settimanale su codice datato             | `dependabot.yml` apre PR di bump; il pin è documentato con commento inline (§3.1 G-2)                                                      |
| R-2 | `--noupdate` hardcodato → DB NVD assente sul runner                | job OWASP rosso (solo `schedule`)            | `continue-on-error: true` (G-2b) + nota in `docs/ci.md`; **non** è un check obbligatorio                                                   |
| R-3 | Deriva di formattazione sui Markdown nuovi                         | `repo-hygiene` rosso                         | `./frontend/node_modules/.bin/prettier --write docs/plan/TK-DE445B-wave-G-plan.md docs/qa/*.md docs/architecture/**/*.md` prima del commit |
| R-4 | `@semantic-release/git` non pusha con `persist-credentials: false` | CHANGELOG non committato                     | unica modifica ammessa: `persist-credentials: true`, da annotare in ADR-0013                                                               |
| R-5 | Docker/trivy/cosign assenti in locale                              | immagini mai costruite prima della CI        | validazione statica dei Dockerfile invariata; il primo run verde di `container-images.yml` è la prova                                      |
| R-6 | Token in chiaro in `.git/config`                                   | esposizione se il workspace viene archiviato | mai copiare l'URL con credenziali in file, log o report; G-7 non deve contenere output di `git remote -v`                                  |

---

## 7. SKIP analysis (domini non impattati)

**FRONTEND_SKIP: nessuna modifica al frontend.** Zero file sotto `frontend/src/**`, nessuna pagina,
rotta, componente, form, tipo o test TS. Nemmeno `package.json`/`package-lock.json` vengono toccati
(`js-yaml` è già dichiarato da Wave F). I 18 test Vitest devono restare verdi e il bundle identico.

**DB_SKIP: nessuna modifica a schema, migrazioni o query.** `V1__init_claims.sql`,
`V1__init_documents.sql`, `infra/db/init/01-init-schemas.sql` e i test
`SchemaMigrationContractTest` restano invariati; nessuna migrazione, nessun seed.

**BACKEND_SKIP: nessuna modifica ai 3 servizi Java.** Zero file `.java`, zero `application*.yml`,
zero `pom.xml`. `./mvnw -B -ntp verify` deve produrre lo stesso esito registrato in
`docs/plan/TK-DE445B-wave-F-closing-plan.md` §0.2 riga 1. Se un job CI è rosso, si corregge il job,
**non** il codice.
