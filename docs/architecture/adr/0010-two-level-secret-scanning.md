# ADR-0010 — Two-level secret scanning with no mandatory secret

- **Status**: accepted
- **Date**: iteration following `2dc711a`

## Context

`gitleaks/gitleaks-action@v2` requires `GITLEAKS_LICENSE` for private repositories owned by an
organisation. AC-7 must not depend on an external licence being purchased.

## Decision

1. `secret-scan.yml` runs gitleaks over the full history (`fetch-depth: 0`) with `.gitleaks.toml`; the
   `GITLEAKS_LICENSE` secret is documented as **optional**.
2. `ci.yml` (`repo-hygiene`) runs an always-on, deterministic `grep` gate for `ghp_`, `github_pat_`,
   `AKIA…` and PEM private key headers, which must produce empty output.

## Consequences

- Secret scanning can never be silenced by a missing licence or a misconfigured action.
- The grep gate is redundant but cheap and predictable; it also runs in the local definition of done.
- Allowlisted paths (`.env.example`, plan documents, examples) may show redacted patterns by design.
