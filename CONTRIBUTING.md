# Contributing to the Claims Platform

## 1. Commit messages — Conventional Commits

```
<type>(<scope>): <summary>
```

Allowed types: `feat`, `fix`, `chore`, `docs`, `refactor`, `test`, `build`, `ci`, `perf`.
Suggested scopes: `core-app`, `document-service`, `bff`, `frontend`, `infra`, `ci`, `db`, `docs`.

`semantic-release` derives the version from these types (`feat` → minor, `fix` → patch, everything
else → no release), so a wrong type is a wrong release. See [`docs/versioning.md`](docs/versioning.md).

## 2. Branches

```
feat/TK-xxx-short-slug
fix/TK-xxx-short-slug
chore/TK-xxx-short-slug
docs/TK-xxx-short-slug
```

`main` is protected: no direct push, squash merge only, the `ci-success` check must be green and the
`CODEOWNERS` review must be approved.

## 3. Pull request flow

1. Open a branch from `main`, keep it focused on one ticket.
2. Run the local definition of done (section 5) before pushing.
3. Fill in [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) — the AC checklist
   and the API/DB impact sections are mandatory.
4. Review by the owners declared in [`.github/CODEOWNERS`](.github/CODEOWNERS).
5. Squash merge with a Conventional Commit title (the `pr-title` workflow validates it).

## 4. Code style

| Stack   | Format                                            | Lint                             | Check in CI    |
| ------- | ------------------------------------------------- | -------------------------------- | -------------- |
| Java    | `./mvnw -B spotless:apply`                        | `./mvnw -B checkstyle:check`     | `mvnw verify`  |
| TS/TSX  | `npm --prefix frontend run format`                | `npm --prefix frontend run lint` | `frontend-ci`  |
| MD/YAML | `./frontend/node_modules/.bin/prettier --write .` | same                             | `repo-hygiene` |

Rules: LF everywhere (`.gitattributes`), UTF-8, 100 columns for Markdown/YAML/TS, 120 for Java,
no unused imports, no `*` CORS origin, no wildcard SQL schema.

## 5. Local definition of done

```bash
./mvnw -B -ntp verify
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:run
npm --prefix frontend run build
./frontend/node_modules/.bin/prettier --check .
```

Then check that no secret slipped in:

```bash
grep -rniE '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist
```

The command must print nothing.

## 6. Secrets

- Never commit `.env`, `.env.*` (except `.env.example`), tokens, passwords, private keys or
  certificates.
- New configuration variables are documented **only** as placeholders (`__change_me__`) in
  `.env.example`, together with the module that consumes them.
- CI reads credentials from repository secrets and OIDC; a workflow must never contain a literal
  credential.

## 7. API and database changes

- Changing an endpoint means updating the matching reviewed snapshot in `docs/api/` in the same PR
  (ADR-0009) and keeping the frontend types in `frontend/src/types/claims.ts` structurally identical.
- Schema changes are forward-only Flyway migrations: add `V<n>__description.sql`, never edit an
  applied one, never let Hibernate create the schema (`ddl-auto` stays `validate`). The
  `SchemaMigrationContractTest` in both services guards migrations against entity drift.

## 8. Container and infrastructure changes

Dockerfiles live in `infra/docker/`. Every image must stay multi-stage, run as a non-root user and
declare a `HEALTHCHECK`. Images are built and signed only in CI — Docker is not available in the
development sandbox, so validate statically and rely on the `container-images` workflow.
