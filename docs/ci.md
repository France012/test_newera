# CI/CD

Every workflow lives in `.github/workflows/`. They are the only place where build, test, scan,
image-signing and release logic is defined — nothing is duplicated in scripts that could drift.

## Workflow map

| Workflow               | Triggers                                          | Permissions                                                                   | Artefacts / effects                                                                                              |
| ---------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `ci.yml`               | push/PR on `main`, manual                         | `contents: read`                                                              | `backend-reports`, `frontend-dist`; required check `ci-success`                                                  |
| `dependency-scan.yml`  | push/PR on `main`, weekly (Mon 03:00 UTC), manual | `contents: read`, `security-events: write`                                    | Trivy SARIF, `npm-audit`, OWASP HTML report                                                                      |
| `codeql.yml`           | push/PR on `main`, weekly (Tue 05:00 UTC), manual | `contents: read`, `security-events: write`, `packages: read`, `actions: read` | CodeQL results for `java-kotlin` + `javascript-typescript`                                                       |
| `secret-scan.yml`      | push/PR on `main`, weekly (Wed 06:00 UTC), manual | `contents: read`                                                              | gitleaks result + deterministic grep gate                                                                        |
| `container-images.yml` | push on `main`, tag `v*`, manual                  | `contents: read`, `packages: write`, `id-token: write`, `attestations: write` | 4 GHCR images, 4 `sbom-<service>` artefacts, 1 `sbom-claims-platform`, cosign signatures, provenance attestation |
| `release.yml`          | push on `main`, manual                            | `contents: write`, `issues: write`, `pull-requests: write`                    | tag `vX.Y.Z`, `CHANGELOG.md`, GitHub Release                                                                     |
| `pr-title.yml`         | PR opened/edited/synchronize/reopened             | `contents: read`, `pull-requests: read`                                       | Conventional Commits validation                                                                                  |

Common conventions: minimal `permissions` at file level, `concurrency` with `cancel-in-progress`,
explicit `timeout-minutes` on every job, actions pinned to a major tag and kept current by
Dependabot, `actions/upload-artifact@v4` with `retention-days: 7` (30 for SBOMs and OWASP reports).

## `ci.yml` jobs

| Job            | What it proves                                                                                                             |
| -------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `java-verify`  | AC-2/AC-11 — `./mvnw -B -ntp verify`: compile, unit tests, Spotless, Checkstyle, JaCoCo on 3 modules                       |
| `frontend-ci`  | AC-3 — `npm ci`, `lint`, `format:check`, `test:run`, `build` (which already runs `typecheck`)                              |
| `repo-hygiene` | AC-7/AC-12 — Prettier at repo root, YAML/OpenAPI parsing (ADR-0009), secret grep gate, `VERSION` == root `pom.xml` version |
| `ci-success`   | Aggregated result — the **only** check to require in branch protection                                                     |

Known pitfalls encoded in the workflow comments: use `npm run test:run` (plain `npm test` starts
Vitest in watch mode and hangs the job) and do not add a separate `tsc` step (`build` already runs
`typecheck`).

## Secrets

| Secret             | Required | Used by                        | Notes                                                       |
| ------------------ | -------- | ------------------------------ | ----------------------------------------------------------- |
| `GITHUB_TOKEN`     | implicit | all workflows                  | Provided by GitHub; scoped by the `permissions:` block      |
| `NVD_API_KEY`      | optional | `dependency-scan.yml` (weekly) | Without it the OWASP run is slower and rate-limited         |
| `GITLEAKS_LICENSE` | optional | `secret-scan.yml`              | Required by gitleaks only for private repos owned by an org |

**No long-lived personal access token is used anywhere.** Keyless `cosign` signing relies on the OIDC
token minted per workflow run (`id-token: write`).

## Reproduce the pipeline locally

```bash
# 1. Backend equivalent of java-verify
./mvnw -B -ntp verify

# 2. Frontend equivalent of frontend-ci
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:run
npm --prefix frontend run build

# 3. repo-hygiene: formatting (single Prettier install, ADR-0008)
./frontend/node_modules/.bin/prettier --check .

# 4. repo-hygiene: YAML / OpenAPI snapshots must parse
node -e "const y=require('./frontend/node_modules/js-yaml'),fs=require('fs');for(const f of process.argv.slice(1)){y.load(fs.readFileSync(f,'utf8'));console.log('OK '+f)}" \
  docs/api/*.openapi.yaml infra/docker-compose.yml .github/workflows/*.yml

# 5. repo-hygiene: secret gate must print nothing
grep -rniE '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist
```

## What cannot be verified locally

The development sandbox has **no Docker, cosign, syft or trivy**. Therefore the following are only
proven by CI, and a local "all green" is not a full proof:

- `docker build` of the four images and `docker compose up` against PostgreSQL 16;
- `cosign sign` / `cosign verify` keyless signatures;
- SBOM generation and upload as artefacts;
- SARIF upload, CodeQL analysis and semantic-release.

Dockerfiles are validated statically before pushing: at least two `FROM` stages, a non-root `USER`,
a `HEALTHCHECK`, an `EXPOSE` matching `application.yml`, and no credential-looking assignment.

## Deliberate gaps (technical debt)

- **SpotBugs** and `config/checkstyle/suppressions.xml` are intentionally absent (ADR-0012): the root
  POM does not reference them, so adding them now would be dead configuration. Static security
  coverage is provided by CodeQL + Trivy/OWASP.
- **Trivy is report-only** on push/PR in phase 1 (`exit-code: 0`). Transitive CVEs without a fix must
  not block delivery; the blocking gates today are `npm audit --audit-level=critical`, CodeQL, the
  secret gate and `mvn verify`.
- `container-images.yml` runs twice for a release (once on the `main` push, once on the tag created by
  `release.yml`). Accepted: it guarantees the semver tag always exists on the registry.
