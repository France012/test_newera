# `infra/db/verify` — executable schema contract

Read-only SQL assertion script for the persistence layer (TK-DE445B).

| File                   | Purpose                                                                        |
| ---------------------- | ------------------------------------------------------------------------------ |
| `01-assert-schema.sql` | Fails (exit 1) if `coreapp` / `documents` drift from `docs/db/README.md` §4    |

## Why it exists

Two guards already protect the schema, and they cover **different** halves of the problem:

| Guard                                          | Runs where                   | Proves                                                        |
| ---------------------------------------------- | ---------------------------- | ------------------------------------------------------------- |
| `SchemaMigrationContractTest` (both modules)   | `mvn verify`, no DB needed   | JPA entities ⇄ migration **text** are consistent              |
| `01-assert-schema.sql` (this folder)           | after `flyway migrate`       | PostgreSQL **accepted** the migration and the objects are real |

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
 SCHEMA CONTRACT OK   |                92 | 6 tables · 2 schemas · 29 named constraints ...
```

Failure (exit code 1, first violation aborts):

```
ERROR:  SCHEMA CONTRACT VIOLATION: coreapp.claims.status must be varchar(32) NOT NULL ...
HINT:   docs/db/README.md §4 (table reference) / §7-bis (contract tests): ...
```

## Guarantees

- **Read-only**: only `pg_temp.*` helper functions are created; no table, row or
  committed object is touched.
- **Schema-only**: it does not seed data and does not depend on the application.
- **Deterministic**: every assertion is a catalog query (`information_schema` /
  `pg_catalog`), so the result does not depend on the order of the migrations or on
  `search_path` (all objects are schema-qualified).

## Maintenance rule

A new `V<n>__*.sql` migration must update this file **in the same pull request**, exactly
like the entity and the Java contract test. Never relax an assertion to make a pipeline
pass — fix the migration or the design document (`docs/db/README.md`, the source of truth).
