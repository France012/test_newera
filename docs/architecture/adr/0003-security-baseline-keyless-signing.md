# ADR-0003 — Security baseline: keyless signing and automated scanning

- **Status**: accepted
- **Date**: iteration `0.1.0`

## Context

The platform must prove where its container images come from and must not depend on long-lived
credentials for signing. Any signing key stored in CI is a credential to protect and rotate.

## Decision

- Images are signed with `cosign` in **keyless** mode (OIDC identity from GitHub Actions,
  `id-token: write`); no private key exists in the repository or in any secret.
- Provenance is attested explicitly with `actions/attest-build-provenance` and BuildKit provenance is
  disabled so that the signed object is the image manifest the verifiers expect.
- Signed **by digest**, never by tag.
- Automated controls on every push: secret scanning, CodeQL SAST, Trivy SCA, `npm audit`.

## Consequences

- Verification requires an identity regexp and the GitHub OIDC issuer, not a public key file.
- Rotation is unnecessary; compromising the repository does not compromise the signing key.
- Signing can only happen inside CI (or with a human OIDC login) — never in the dev sandbox.
