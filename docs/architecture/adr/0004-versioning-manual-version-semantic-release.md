# ADR-0004 — Versioning: manual `VERSION` file plus semantic-release tags

- **Status**: accepted
- **Date**: iteration `0.1.0`

## Context

The build must know its version without network access or git history (a source archive must still
compile), while release notes and tags should be generated automatically from Conventional Commits.

## Decision

The version lives in the `VERSION` file and in the root `pom.xml` `<version>`, kept identical by a
CI check. `semantic-release` (`.releaserc.json`, `release.yml`) derives the tag `vX.Y.Z`, the
`CHANGELOG.md` entry and the GitHub Release from commit types, but does **not** bump `VERSION` or the
POM. That bump is an explicit, reviewable commit.

## Consequences

- `mvn` builds always work offline and on a shallow clone.
- A release may need two commits (the version bump and the semantic-release commit); this is a
  conscious trade-off in favour of reproducibility.
- Version numbers can never drift between `VERSION` and `pom.xml` without failing `repo-hygiene`.
