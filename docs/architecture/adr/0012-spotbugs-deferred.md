# ADR-0012 — SpotBugs and Checkstyle suppressions deferred

- **Status**: accepted (conscious deviation from blueprint §A16/A17)
- **Date**: iteration following `2dc711a`

## Context

The implemented root `pom.xml` contains no SpotBugs plugin, and `config/checkstyle/suppressions.xml`
is not referenced by any POM. Creating those files now would be dead configuration nobody executes.

## Decision

Do **not** add `config/spotbugs/exclude.xml` or `config/checkstyle/suppressions.xml` in this
iteration. Static security coverage is provided by CodeQL (SAST) and Trivy / OWASP Dependency-Check
(SCA). SpotBugs is recorded as technical debt in `docs/ci.md`.

## Consequences

- The build behaviour is unchanged and cannot break on formatting already normalised by
  google-java-format.
- A future iteration that wires SpotBugs into `verify` must add both the plugin and its exclusion
  file in the same change.
