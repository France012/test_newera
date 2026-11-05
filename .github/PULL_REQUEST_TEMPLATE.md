# Pull request

## Ticket

<!-- e.g. TK-DE445B -->

TK-

## Type of change

- [ ] `feat` — new functionality
- [ ] `fix` — defect correction
- [ ] `chore` — maintenance / tooling
- [ ] `docs` — documentation only
- [ ] `refactor` / `perf`
- [ ] `ci` / `build`

## Summary

<!-- What changes and why. Link the review evidence. -->

## Acceptance criteria checklist

- [ ] AC-1 modules still separated (`core-app`, `document-service`, `bff`, `frontend`, `infra`)
- [ ] AC-2 Java 21 build + Spotless + Checkstyle green (`./mvnw -B verify`)
- [ ] AC-3 frontend lint / format / typecheck green
- [ ] AC-4 CI (build + unit test + lint) green on this branch
- [ ] AC-5 SCA has run (Trivy + `npm audit`)
- [ ] AC-6 SAST (CodeQL) has run
- [ ] AC-7 secret scanning clean (gitleaks + `grep` gate)
- [ ] AC-8 container images still multi-stage, non-root, with `HEALTHCHECK`
- [ ] AC-9 images will be signed in CI when merged to `main`
- [ ] AC-10 SBOM still produced as an artefact
- [ ] AC-12 no secret added to the repository
- [ ] AC-13 governance files still coherent (`VERSION`, `CHANGELOG.md`, `CODEOWNERS`)

## API impact

- [ ] No API change
- [ ] Contract updated: `docs/api/core-app.openapi.yaml`
- [ ] Contract updated: `docs/api/document-service.openapi.yaml`
- [ ] Contract updated: `docs/api/bff.openapi.yaml`

Breaking change? <!-- yes/no + migration note for the frontend -->

## Database impact

- [ ] No migration needed
- [ ] New Flyway migration `V__` added (forward-only, never edited after apply)
- [ ] `ddl-auto` still `validate` · `SchemaMigrationContractTest` green

## Verification evidence

<!-- Paste the commands you ran and their result. -->

```bash
./mvnw -B -ntp verify
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:run
npm --prefix frontend run build
./frontend/node_modules/.bin/prettier --check .
```

## Security confirmation

- [ ] No secret, token, password or private key in the diff
- [ ] No `.env` file committed (only `.env.example` with `__change_me__` placeholders)
- [ ] New configuration variables documented in `.env.example`

## Rollback plan

<!-- How to revert safely: feature flag, revert commit, image rollback by digest. -->
