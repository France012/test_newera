# ADR-0001 — Monorepo with a Maven multi-module aggregator

- **Status**: accepted
- **Date**: iteration `0.1.0`

## Context

Three deployable Spring Boot services (`core-app`, `document-service`, `bff`) and one frontend must
share build conventions (Java release, formatter, linter, coverage, dependency versions) without
copy-pasting POM fragments, while staying independently buildable and releasable.

## Decision

One repository with five top-level modules. The root `pom.xml` is an aggregator (`packaging: pom`)
and parent for the three services; it declares Java 21, Spring Boot 3.3.5, the compiler settings,
Spotless, Checkstyle, JaCoCo and the CycloneDX plugin **once** in `pluginManagement`/`plugins`.
Modules inherit and add only their own dependencies. The frontend is an independent npm project; the
services have **no** Maven dependency on each other.

## Consequences

- A module cannot drift from the platform conventions: bumping the Java version is a one-line change.
- `./mvnw -B verify` at the root is the single backend gate used by CI.
- Independent release of a single service requires `-pl <module> -am`; versioning is still
  repo-wide (ADR-0004), which is acceptable for a monorepo delivered as one product.
