# ADR-0005 — Contracts-first OpenAPI snapshots

- **Status**: accepted
- **Date**: iteration `0.1.0`

## Context

`springdoc` generates the OpenAPI document at runtime, but the frontend needs a stable, reviewable
contract in the repository, and reviewers must see API changes in the pull request diff rather than
in a running server.

## Decision

The three files under `docs/api/` (`core-app`, `document-service`, `bff`) are committed snapshots
written against the real controllers. They are versioned with the code, referenced by the PR
template's "API impact" section, and validated (YAML parsing) by the `repo-hygiene` job.

## Consequences

- API changes are visible in review and cannot be merged without touching the snapshot.
- The runtime `springdoc` output remains the source for Swagger UI; the snapshot is not generated
  automatically, so drift is caught by review, not by a compiler.
- The frontend types in `frontend/src/types/claims.ts` are kept structurally identical to the BFF
  schemas.
