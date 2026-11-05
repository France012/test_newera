# ADR-0002 — Hexagonal architecture per service

- **Status**: accepted
- **Date**: iteration `0.1.0`

## Context

Business rules (claim state machine, document validation) must be testable without Spring, without a
database and without HTTP, and the persistence/storage technology must be replaceable (PostgreSQL
now, S3 for documents later).

## Decision

Each service is split into `domain` (model + ports), `application` (services + DTOs + exceptions) and
`adapter` (`in/web` controllers, `out/persistence` JPA, `out/storage` filesystem). Controllers are
thin: they translate HTTP only. Transactions and invariants live in the application services.
Persistence adapters implement domain ports and map JPA entities ⇄ domain models.

## Consequences

- Unit tests of `ClaimService` and `DocumentService` run without a Spring context.
- Swapping the storage backend means implementing `DocumentStoragePort` only.
- Slight duplication between JPA entities and domain models — accepted, it keeps the domain free of
  JPA annotations and of lazy-loading surprises.
