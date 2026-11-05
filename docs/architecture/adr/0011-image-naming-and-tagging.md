# ADR-0011 — Image naming and tagging for GHCR

- **Status**: accepted
- **Date**: iteration following `2dc711a`

## Context

GHCR rejects uppercase characters in repository paths, while the GitHub owner is `France012`. The
default provenance of `docker/build-push-action` also changes what a digest refers to.

## Decision

- Image path: `ghcr.io/<owner-lowercase>/<repo-lowercase>/<service>`, computed in a dedicated step
  that lowercases owner and repository name.
- Tags from `docker/metadata-action`: `X.Y.Z` and `X.Y` on `v*` tags, `sha-<short>` on every build,
  `latest` only on `main`.
- `provenance: false` on `build-push-action`; provenance is attested explicitly with
  `actions/attest-build-provenance`.
- If GHCR rejects `test_newera`, only the **image path** falls back to `test-newera`; the GitHub
  repository name is never renamed.

## Consequences

- Signing and verification work on the plain image manifest, not on an unexpected manifest list.
- Kubernetes manifests reference images by `@sha256:` digest, so a moved tag cannot change a
  deployment.
