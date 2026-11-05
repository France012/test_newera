# ADR-0009 — OpenAPI snapshots versioned with a YAML parsing gate

- **Status**: accepted
- **Date**: iteration following `2dc711a`

## Context

The contracts in `docs/api/` are hand-maintained snapshots; nothing verified even that they are valid
YAML, and downloading an extra CLI (`swagger-cli`, `redocly`) in CI was undesirable.

## Decision

The three `docs/api/*.openapi.yaml` files are the reviewed snapshot of the API (written against the
real controllers). The `repo-hygiene` job parses them plus every workflow and compose file with
`js-yaml` taken from `frontend/node_modules` — no additional network dependency.

## Consequences

- Broken YAML can never be merged (AC verified offline).
- Code ⇄ contract drift is caught by human review, guided by the PR template checklist; a full
  automated diff against the runtime `springdoc` output is future work.
