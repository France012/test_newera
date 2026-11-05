# `infra/db/verify` — executable schema contract

Read-only SQL assertion script for the persistence layer (TK-DE445B, extended by TK-64FBC8).

| File                   | Purpose                                                                     |
| ---------------------- | --------------------------------------------------------------------------- |
| `01-assert-schema.sql` | Fails (exit 1) if `coreapp` / `documents` drift from `docs/db/README.md` §4 |

Coverage after TK-64FBC8: **200 assertions** over **12 tables** (6 original + 6 new
`coreapp` tables: `roles`, `actors`, `actor_roles`, `claim_assignments`, `audit_events`,
`claim_status_transition`), the 13 columns added by the two `V2` migrations, and the
state-machine rules stored in the database.

## Why it exists

Two guards already protect the schema, and they cover **different** halves of the problem:

| Guard                                          | Runs where                 | Proves                                                         |
| ---------------------------------------------- | -------------------------- | -------------------------------------------------------------- |
| `SchemaMigrationContractTest` (both modules)   | `mvn verify`, no DB needed | JPA entities ⇄ migration **text** are consistent               |
| `ClaimStatusTransitionContractTest` (core-app) | `mvn verify`, no DB needed | `ClaimStatus.allowedTransitions()` ⇄ the `V2` transition seed  |
| `01-assert-schema.sql` (this folder)           | after `flyway migrate`     | PostgreSQL **accepted** the migration and the objects are real |

Neither is redundant: the Java test never opens a connection, so on its own a migration
could be syntactically invalid or semantically rejected by PostgreSQL and every build
would still be green.

## Usage

```bash
# the migrations must already be applied to the target database
psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/$POSTGRES_DB" \
     -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
```

Success (exit code 0):

```
        result        | assertions_passed |                                    checked ...
----------------------+-------------------+--------------------------------------------
 SCHEMA CONTRACT OK   |               200 | 12 tables · 2 schemas · 77 named constraints ...
```

Failure (exit code 1, first violation aborts):

```
ERROR:  SCHEMA CONTRACT VIOLATION: coreapp.claims.status must be varchar(32) NOT NULL ...
HINT:   docs/db/README.md §4 (table reference) / §7-bis (contract tests): ...
```

## The transition rules are asserted too

Since TK-64FBC8 the allowed claim-status transitions live in `coreapp.claim_status_transition`
and are enforced by PostgreSQL through the composite foreign key
`fk_claim_status_history_transition`. This script pins **set equality** between the seeded
pairs and the state machine documented in `docs/db/README.md` §3:

- exactly 14 pairs, no self-transition (`ck_claim_status_transition_actual_change`);
- `DRAFT` is the only entry point and `ARCHIVED` the only terminal state (no pair starts
  from `ARCHIVED`);
- `APPROVED → SETTLEMENT → CLOSED` is the only way to close an approved claim: the removed
  `APPROVED → CLOSED` (decision A10) must never reappear — otherwise liquidazione could be
  skipped again;
- the two `CHECK` constraints of the rules table accept exactly the 10 `ClaimStatus` values,
  so an enum value added without a migration fails here.

The same parity is checked without a database by `ClaimStatusTransitionContractTest`, which
compares the enum with the `insert … values` seed of `V2__claim_workflow_actors_audit.sql`.

## Guarantees

- **Read-only**: only `pg_temp.*` helper functions are created; no table, row or
  committed object is touched.
- **Schema-only**: it does not seed data and does not depend on the application.
- **Deterministic**: every assertion is a catalog query (`information_schema` /
  `pg_catalog`), so the result does not depend on the order of the migrations or on
  `search_path` (all objects are schema-qualified).

## Maintenance rule

A new `V<n>__*.sql` migration must update this file **in the same pull request**, exactly
like the entity and the Java contract tests (`SchemaMigrationContractTest` in each module and,
for the state machine, `ClaimStatusTransitionContractTest`). Never relax an assertion to make a pipeline
pass — fix the migration or the design document (`docs/db/README.md`, the source of truth).
