# bff

Backend for frontend: the only service the React application talks to. Listens on **8185**, base path
`/bff/v1`. Stateless — no database, no schema.

Build alone with `./mvnw -B -pl bff -am verify`.

## API (contract of record: `docs/api/bff.openapi.yaml`)

| Method | Path | Aggregates |
|---|---|---|
| GET | `/bff/v1/claims` | core-app `GET /api/v1/claims` (`status?`, `page`, `size` capped at 100) |
| GET | `/bff/v1/claims/{id}` | core-app claim **+** document-service documents, one round trip |
| GET | `/bff/v1/dashboard/summary` | claim counters per status from core-app |
| GET | `/actuator/health` | liveness/readiness |

## Configuration (environment only, nothing hardcoded)

| Variable | Purpose | Default |
|---|---|---|
| `CORE_APP_BASE_URL` | core-app base URL | `http://localhost:8180` |
| `DOCUMENT_SERVICE_BASE_URL` | document-service base URL | `http://localhost:8181` |
| `BFF_ALLOWED_ORIGINS` | comma-separated browser origins | `http://localhost:5199` |
| `BFF_CONNECT_TIMEOUT_MS` / `BFF_READ_TIMEOUT_MS` | HTTP timeouts | `2000` / `5000` |

A wildcard origin is filtered out on purpose: credentials are allowed, and the CORS spec forbids `*`
in that case.

## Error mapping

| Situation | Response |
|---|---|
| downstream returns 404 | `404 ProblemDetail` ("Resource not found") |
| downstream returns any other error | `502 ProblemDetail` ("Downstream service error") |
| downstream unreachable / timeout | `503 ProblemDetail` ("Downstream service unavailable") |

The downstream response body is never relayed, so internal details do not leak to the browser.

## Design notes

* **Transport types are separate from API types**: `client/ClaimPagePayload` and
  `client/DocumentPagePayload` model what the downstream services send; `web/dto/*` is what the
  browser receives. `status` is a `String` here on purpose — a new claim status must not break the BFF,
  and the state machine belongs to core-app.
* **Fail fast, but isolate**: each downstream has its own `RestClient` bean, base URL and timeouts.
* **Security baseline**: `SecurityConfig` is a stateless `permitAll` placeholder with an explicit
  TODO; real OIDC is deferred to ADR-0005. The chain exists so CORS preflight is handled once and no
  generated password is printed at startup.

## Running locally

```bash
export CORE_APP_BASE_URL=http://localhost:8180
export DOCUMENT_SERVICE_BASE_URL=http://localhost:8181
export BFF_ALLOWED_ORIGINS=http://localhost:5199
./mvnw -B -pl bff -am spring-boot:run
curl -s localhost:8185/actuator/health
```

## Tests

`BffClaimControllerTest` and `DashboardControllerTest`: `@WebMvcTest` with mocked clients and the real
`SecurityConfig`/`CorsConfig` imported, so the security chain and the CORS bean are exercised without
starting the services. No network access needed.

```bash
./mvnw -B -pl bff -am verify
```
