# ADR-0008 — A single Prettier installation, no root `package.json`

- **Status**: accepted
- **Date**: iteration following `2dc711a`

## Context

Prettier must format Markdown, workflow YAML and `infra/*.yml` at the repository root, but adding a
root `package.json` would introduce a second lockfile and a second `npm ci` in every job.

## Decision

`frontend/` remains the only Node project. CI runs `./frontend/node_modules/.bin/prettier --check .`
from the root, using the root `.prettierrc.json` and `.prettierignore` (which excludes `frontend/`,
where the project's own `.prettierrc.json` applies). The two configurations are byte-identical:
`printWidth 100`, `singleQuote`, `semi`, `trailingComma: all`, `arrowParens: always`, `endOfLine: lf`.

## Consequences

- One `npm ci` per job, no duplicate Node dependency graph, no second lockfile to audit.
- A Prettier upgrade must be applied in `frontend/package.json` and the option values kept in sync in
  both config files (they are compared by review, and by `prettier --check` in practice).
