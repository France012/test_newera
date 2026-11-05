# ADR-0006 — SCA: Trivy as the primary gate, OWASP Dependency-Check weekly

- **Status**: accepted
- **Date**: iteration following `2dc711a`

## Context

`dependency-check-maven` downloads the NVD feed (minutes, rate-limited without `NVD_API_KEY`) and
would make every push slow and fragile, while transitive CVEs without an available fix must not block
delivery in phase 1.

## Decision

1. On **every** push/PR: `aquasecurity/trivy-action` in `fs` mode covering `pom.xml` and
   `package-lock.json`, SARIF uploaded to code scanning with `exit-code: 0` (report-only).
2. `npm audit --audit-level=critical` is **blocking**; `high` is reported.
3. OWASP Dependency-Check runs only on `schedule` (weekly) and `workflow_dispatch`, with an optional
   `NVD_API_KEY` and `--failOnCVSS 9`.

## Consequences

- AC-5 is satisfied with two complementary levels; the blocking gate is documented in `docs/ci.md`.
- No mandatory secret: without `NVD_API_KEY` the weekly job is merely slower.
- The blocking posture tightens in phase 2 by flipping Trivy to `exit-code: 1` on CRITICAL.
