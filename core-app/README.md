# core-app

Claims, policies and the claim status state machine. Listens on **8180**, base path `/api/v1`.

Part of the `claims-platform` Maven aggregator; build it alone with
`./mvnw -B -pl core-app -am verify`.

## API (contract of record: `docs/api/core-app.openapi.yaml`)

| Method | Path                                 | Notes                                                                                                      |
| ------ | ------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| GET    | `/api/v1/claims`                     | `status?`, `page=0`, `size=20`, `sort=openedAt,desc` (`createdAt` accepted as alias, `size` capped at 100) |
| GET    | `/api/v1/claims/{id}`                | 404 `ProblemDetail` when unknown                                                                           |
| POST   | `/api/v1/claims`                     | 201 + `Location`; claim always starts in `DRAFT`                                                           |
| PATCH  | `/api/v1/claims/{id}/status`         | 409 `ProblemDetail` on an illegal transition                                                               |
| GET    | `/api/v1/claims/{id}/status-history` | audit trail, chronological                                                                                 |
| POST   | `/api/v1/claims/{id}/documents`      | attaches document-service ids, idempotent                                                                  |
| GET    | `/api/v1/policies`                   | paginated read model                                                                                       |
| GET    | `/api/v1/policies/{id}`              | 404 when unknown                                                                                           |
| GET    | `/actuator/health`                   | liveness/readiness                                                                                         |

Errors are RFC 9457 `application/problem+json` (single `GlobalExceptionHandler`).

## Business rules implemented here

1. A claim can only be opened on an **existing policy** (`policyNumber` lookup) - otherwise 404.
2. The `incidentDate` must fall **inside the policy cover period**, and a `customerId` sent by the
   client must match the policy owner - otherwise 400.
3. `POST /api/v1/claims` always creates a `DRAFT` claim; there is no way to inject a status.
4. Status changes are validated against the `ClaimStatus` state machine
   (`DRAFT→SUBMITTED→UNDER_REVIEW→{APPROVED|REJECTED|INTEGRATION_REQUESTED}→CLOSED`); anything else
   is a 409 that lists the allowed targets.
5. Every transition (including the opening one) appends a row to `claim_status_history` in the same
   transaction as the status update.

## Layout (hexagonal-lite, blueprint §2.7)

```
domain/model      Claim, ClaimStatus (owned by the data layer), ClaimStatusHistory, Policy, Customer
domain/port       ClaimRepositoryPort, PolicyRepositoryPort, ClaimDocumentPort
application       ClaimService, PolicyService, dto, exception
adapter/in/web    ClaimController, PolicyController, GlobalExceptionHandler, PageRequests
adapter/out/...   *RepositoryAdapter (entity <-> domain), entities and Spring Data repositories
config            ClockConfig, OpenApiConfig
```

## Running locally

```bash
# PostgreSQL is required: Flyway migrates schema coreapp and Hibernate validates it.
export POSTGRES_USER=<user> POSTGRES_PASSWORD=<password>
./mvnw -B -pl core-app -am spring-boot:run -Dspring-boot.run.profiles=local
curl -s localhost:8180/actuator/health
```

`spring.jpa.hibernate.ddl-auto` is `validate` in every profile: the schema belongs to Flyway
(`src/main/resources/db/migration`), see `docs/db/README.md`.

## Tests

Unit and slice tests only (`Mockito`, `@WebMvcTest`): the build never needs PostgreSQL.

```bash
./mvnw -B -pl core-app -am verify
```
