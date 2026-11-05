# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 0.1.x   | ✅        |

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

1. Use GitHub **Security → Report a vulnerability** (private advisory) on
   `github.com/France012/test_newera`, or
2. Contact the maintainers listed in [`.github/CODEOWNERS`](.github/CODEOWNERS) through the internal
   ticketing system, referencing this policy.

Include: affected module (`core-app`, `document-service`, `bff`, `frontend`, `infra`), version or
commit, reproduction steps, impact and, if available, a suggested fix. You will receive an
acknowledgement within 3 working days and a remediation plan within 10 working days for confirmed
issues.

## Secret management policy

- **No credential is ever committed.** Environment files are git-ignored except
  [`.env.example`](.env.example), which only documents the contract using `__change_me__`
  placeholders and the consuming module.
- Local development: a developer copies `.env.example` to an untracked `.env`.
- CI: credentials come from GitHub repository/organisation secrets or from OIDC short-lived tokens
  (`id-token: write` for `cosign` keyless signing). No long-lived PAT is stored in a workflow.
- Kubernetes: values are injected from `Secret` objects, never from `ConfigMap`.
- A leaked credential is revoked first and rotated second; then the history is cleaned and the
  incident recorded in `CHANGELOG.md` under "Security".

## Automated controls

| Control              | Tool                                                           | Trigger              | Cadence                  |
| -------------------- | -------------------------------------------------------------- | -------------------- | ------------------------ |
| Secret scanning      | `gitleaks` + deterministic `grep` gate                         | push / PR / schedule | every push, weekly (Wed) |
| SAST                 | CodeQL (`java-kotlin`, `javascript-typescript`)                | push / PR / schedule | every push, weekly (Tue) |
| SCA (dependencies)   | Trivy `fs` + `npm audit`                                       | push / PR / schedule | every push, weekly (Mon) |
| SCA (deep NVD feed)  | OWASP Dependency-Check                                         | schedule / manual    | weekly, fail on CVSS ≥ 9 |
| Container image scan | Trivy (as part of the image job)                               | image build          | every image build        |
| Supply chain         | CycloneDX SBOM + `cosign` keyless signature + build provenance | image build          | every image build        |

Blast-radius rules applied in code: no `*` CORS origin, no error message/stacktrace leaked in HTTP
responses (`server.error.include-*: never`), RFC 9457 problem details, uploads capped at 20 MB,
soft delete for documents, non-root containers with read-only root filesystem in Kubernetes.

## Dependencies

Dependabot opens weekly pull requests for Maven, npm, GitHub Actions and Docker (limit 5 open PRs
per ecosystem). Security updates are treated as `fix` and released immediately.
