-- =============================================================================
-- TK-DE445B — LOCAL / DEV provisioning only (NOT a Flyway migration)
-- -----------------------------------------------------------------------------
-- Purpose : prepare the PostgreSQL 16 instance used by infra/docker-compose.yml
--           (host port 5442) so that Flyway can create its objects.
-- Runs    : executed by the official postgres image entrypoint when mounted at
--           /docker-entrypoint-initdb.d (i.e. ONLY on an empty data volume).
--           Mount suggestion (from infra/docker-compose.yml):
--             ./db/init:/docker-entrypoint-initdb.d:ro
--
-- IMPORTANT — division of responsibility
--   * This script NEVER creates tables. Tables belong exclusively to Flyway
--     (core-app/.../db/migration/V1__init_claims.sql,
--      document-service/.../db/migration/V1__init_documents.sql).
--   * No credentials, roles-with-passwords or secrets are defined here:
--     POSTGRES_USER / POSTGRES_PASSWORD come from the untracked .env file
--     (only .env.example with placeholders is committed).
--   * Everything is idempotent so it can also be applied manually with psql.
--
-- Manual use (equivalent):
--   psql "postgresql://<user>:<pwd>@localhost:5442/<db>" -f infra/db/init/01-init-schemas.sql
-- =============================================================================

-- Schema owned by core-app (claims, policies, customers, audit trail)
create schema if not exists coreapp;

-- Schema owned by document-service (document metadata)
create schema if not exists documents;

comment on schema coreapp is
    'Owned by core-app (:8180). Tables created by Flyway V1__init_claims.sql.';
comment on schema documents is
    'Owned by document-service (:8181). Tables created by Flyway V1__init_documents.sql.';

-- Least-privilege baseline: the application login role only needs to work inside
-- its own schema. The role name comes from POSTGRES_USER (env), never hardcoded:
-- run the two statements below with the login role substituted, or simply rely on
-- POSTGRES_USER being the owner of the database (default dev behaviour).
--
--   revoke all on schema public from public;
--   grant usage, create on schema coreapp, documents to <app_login_role>;
