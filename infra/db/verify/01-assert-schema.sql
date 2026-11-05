-- =============================================================================
-- TK-DE445B — EXECUTABLE SCHEMA CONTRACT (PostgreSQL 16)
-- -----------------------------------------------------------------------------
-- Owner       : Data Architecture (persistence layer)
-- Companion   : docs/db/README.md §4 (table reference), §7 (migration policy),
--               §7-bis (Java contract tests) and §8-bis (this file)
-- Scope       : READ ONLY. Creates nothing outside pg_temp, changes no data.
--
-- WHY THIS FILE EXISTS
--   The Java `SchemaMigrationContractTest` (docs/db/README.md §7-bis) proves that
--   the JPA entities and the `.sql` migration text agree with each other. It does
--   NOT prove that PostgreSQL accepts the migration: it never opens a connection.
--   This script is the missing half — it runs against a real database AFTER the
--   Flyway migrations have been applied and fails loudly on any drift from the
--   design document (wrong type, lost index, lost CHECK, lost ON DELETE CASCADE).
--
-- WHEN TO RUN
--   * in CI, after `flyway migrate` (or after the V1 + V2 scripts of both
--     modules) against the PostgreSQL service container — see §8-bis for the
--     ready-made job snippet;
--   * locally after `psql -f core-app/.../V1__init_claims.sql` etc.
--   It must be run with ON_ERROR_STOP so the first violation aborts the job.
--
-- HOW TO RUN
--   psql "postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5442/$POSTGRES_DB" \
--        -v ON_ERROR_STOP=1 -f infra/db/verify/01-assert-schema.sql
--
-- HOW TO READ THE OUTPUT
--   Every assertion is a `select pg_temp.assert_schema(...)` returning void, so psql
--   prints one empty row per assertion (200 total): that silence IS the pass signal.
--   Only the last statement prints real values (the summary row below). Redirect with
--   `> /dev/null` if the CI log must stay short — the exit code carries the verdict.
--
-- SUCCESS OUTPUT (exit code 0)
--   one row per assertion group + a final row:
--     SCHEMA CONTRACT OK | 200 assertions_passed | 12 tables, 2 schemas, 77 named
--     constraints, 24 indexes, 10 ClaimStatus values, 14 transition rules
-- FAILURE OUTPUT (exit code 1)
--   ERROR:  SCHEMA CONTRACT VIOLATION: coreapp.claims.status must be varchar(32) NOT NULL
--   HINT:   docs/db/README.md §4 ... — the migration and the design document diverged
--
-- MAINTENANCE RULE
--   A schema change (new V<n>__ migration) MUST update this file in the same pull
--   request, exactly as it must update the entities and the Java contract test.
--   Never weaken an assertion to make a build pass: fix the migration or the doc.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Helpers — session-local (pg_temp) so nothing is ever committed to the schema.
-- -----------------------------------------------------------------------------

-- Fails the script (raise exception -> psql exit code 1) when condition is not true.
create or replace function pg_temp.assert_schema(p_condition boolean, p_message text)
    returns void
    language plpgsql
as $fn$
begin
    if p_condition is distinct from true then
        raise exception 'SCHEMA CONTRACT VIOLATION: %', p_message
            using hint = 'docs/db/README.md §4 (table reference) / §7-bis (contract tests): '
                         'migration, entity and design document diverged.';
    end if;
end;
$fn$;

-- column exists with the expected type, character length and nullability.
create or replace function pg_temp.has_column(
    p_schema text,
    p_table text,
    p_column text,
    p_data_type text,
    p_length integer,
    p_nullable boolean)
    returns boolean
    language sql
    stable
as $fn$
select exists (
    select 1
      from information_schema.columns c
     where c.table_schema = p_schema
       and c.table_name = p_table
       and c.column_name = p_column
       and c.data_type = p_data_type
       and (p_length is null or c.character_maximum_length = p_length)
       and (c.is_nullable = 'YES') = p_nullable
);
$fn$;

-- numeric column has the expected (precision, scale), e.g. policies.premium numeric(12,2).
create or replace function pg_temp.has_numeric_column(
    p_schema text,
    p_table text,
    p_column text,
    p_precision integer,
    p_scale integer)
    returns boolean
    language sql
    stable
as $fn$
select exists (
    select 1
      from information_schema.columns c
     where c.table_schema = p_schema
       and c.table_name = p_table
       and c.column_name = p_column
       and c.data_type = 'numeric'
       and c.numeric_precision = p_precision
       and c.numeric_scale = p_scale
       and c.is_nullable = 'NO'
);
$fn$;

-- primary key / foreign key / unique / check constraint exists by name; the optional
-- pattern must also appear in the constraint definition.
create or replace function pg_temp.has_constraint(
    p_schema text,
    p_table text,
    p_name text,
    p_type "char",
    p_definition_like text default null)
    returns boolean
    language sql
    stable
as $fn$
select exists (
    select 1
      from pg_constraint con
      join pg_class rel on rel.oid = con.conrelid
      join pg_namespace nsp on nsp.oid = rel.relnamespace
     where nsp.nspname = p_schema
       and rel.relname = p_table
       and con.conname = p_name
       and con.contype = p_type
       and (p_definition_like is null
            or pg_get_constraintdef(con.oid) ilike p_definition_like)
);
$fn$;

-- ordered column list of a key constraint, used to pin composite primary keys.
create or replace function pg_temp.key_columns(
    p_schema text,
    p_table text,
    p_name text)
    returns text[]
    language sql
    stable
as $fn$
select array_agg(att.attname::text order by att.attname)
  from pg_constraint con
  join pg_class rel on rel.oid = con.conrelid
  join pg_namespace nsp on nsp.oid = rel.relnamespace
  join pg_attribute att on att.attrelid = con.conrelid and att.attnum = any (con.conkey)
 where nsp.nspname = p_schema
   and rel.relname = p_table
   and con.conname = p_name;
$fn$;

-- ON DELETE rule of a foreign key: 'cascade', 'restrict', 'no action', ...
create or replace function pg_temp.fk_delete_rule(
    p_schema text,
    p_table text,
    p_name text)
    returns text
    language sql
    stable
as $fn$
select case con.confdeltype
           when 'c' then 'cascade'
           when 'r' then 'restrict'
           when 'n' then 'set null'
           when 'd' then 'set default'
           else 'no action'
       end
  from pg_constraint con
  join pg_class rel on rel.oid = con.conrelid
  join pg_namespace nsp on nsp.oid = rel.relnamespace
 where nsp.nspname = p_schema
   and rel.relname = p_table
   and con.conname = p_name
   and con.contype = 'f';
$fn$;

-- index exists; the optional pattern must appear in the index definition
-- (used to pin the partial index predicate `where deleted_at is null`).
create or replace function pg_temp.has_index(
    p_schema text,
    p_table text,
    p_index text,
    p_definition_like text default null)
    returns boolean
    language sql
    stable
as $fn$
select exists (
    select 1
      from pg_indexes i
     where i.schemaname = p_schema
       and i.tablename = p_table
       and i.indexname = p_index
       and (p_definition_like is null or i.indexdef ilike p_definition_like)
);
$fn$;

-- string literals inside a CHECK constraint, sorted. Used to compare a status CHECK
-- against it.enterprise.claims.core.domain.model.ClaimStatus (§7.5 drift rule).
create or replace function pg_temp.check_literals(
    p_schema text,
    p_table text,
    p_name text)
    returns text[]
    language sql
    stable
as $fn$
select coalesce(array_agg(distinct m.literal order by m.literal), array[]::text[])
  from pg_constraint con
  join pg_class rel on rel.oid = con.conrelid
  join pg_namespace nsp on nsp.oid = rel.relnamespace
 cross join lateral regexp_matches(pg_get_constraintdef(con.oid), '''([A-Z_]+)''', 'g')
             as m(literal)
 where nsp.nspname = p_schema
   and rel.relname = p_table
   and con.conname = p_name;
$fn$;

-- =============================================================================
-- 1. Schemas — one per service (database-per-service, DD-5)
-- =============================================================================
select pg_temp.assert_schema(
    exists (select 1 from information_schema.schemata where schema_name = 'coreapp'),
    'schema coreapp must exist (core-app / claims, policies, customers, audit trail)');

select pg_temp.assert_schema(
    exists (select 1 from information_schema.schemata where schema_name = 'documents'),
    'schema documents must exist (document-service metadata)');

-- =============================================================================
-- 2. Tables — all six must exist, in the right schema
-- =============================================================================
select pg_temp.assert_schema(
    to_regclass('coreapp.customers') is not null,
    'table coreapp.customers must exist');

select pg_temp.assert_schema(
    to_regclass('coreapp.policies') is not null,
    'table coreapp.policies must exist');

select pg_temp.assert_schema(
    to_regclass('coreapp.claims') is not null,
    'table coreapp.claims must exist (aggregate root)');

select pg_temp.assert_schema(
    to_regclass('coreapp.claim_status_history') is not null,
    'table coreapp.claim_status_history must exist (append-only audit trail)');

select pg_temp.assert_schema(
    to_regclass('coreapp.claim_documents') is not null,
    'table coreapp.claim_documents must exist (claim <-> document link)');

select pg_temp.assert_schema(
    to_regclass('documents.documents') is not null,
    'table documents.documents must exist (document metadata)');

-- TK-64FBC8 tables: attori, ruoli, assegnazioni, audit trail e regole di stato.
select pg_temp.assert_schema(
    to_regclass('coreapp.roles') is not null,
    'table coreapp.roles must exist (lookup dei ruoli, reference data)');

select pg_temp.assert_schema(
    to_regclass('coreapp.actors') is not null,
    'table coreapp.actors must exist (attori / utenti)');

select pg_temp.assert_schema(
    to_regclass('coreapp.actor_roles') is not null,
    'table coreapp.actor_roles must exist (ruoli concessi a un attore)');

select pg_temp.assert_schema(
    to_regclass('coreapp.claim_assignments') is not null,
    'table coreapp.claim_assignments must exist (attore + ruolo su una pratica)');

select pg_temp.assert_schema(
    to_regclass('coreapp.audit_events') is not null,
    'table coreapp.audit_events must exist (append-only audit trail)');

select pg_temp.assert_schema(
    to_regclass('coreapp.claim_status_transition') is not null,
    'table coreapp.claim_status_transition must exist (state machine rules)');

-- No table may leak into the wrong schema / into public.
select pg_temp.assert_schema(
    not exists (
        select 1
          from information_schema.tables t
         where t.table_schema in ('public', 'coreapp', 'documents')
           and t.table_type = 'BASE TABLE'
           and t.table_name in ('customers', 'policies', 'claims',
                                'claim_status_history', 'claim_documents', 'documents',
                                'roles', 'actors', 'actor_roles', 'claim_assignments',
                                'audit_events', 'claim_status_transition')
           and not (
                    (t.table_schema = 'coreapp'
                     and t.table_name in ('customers', 'policies', 'claims',
                                          'claim_status_history', 'claim_documents',
                                          'roles', 'actors', 'actor_roles',
                                          'claim_assignments', 'audit_events',
                                          'claim_status_transition'))
                 or (t.table_schema = 'documents' and t.table_name = 'documents')
                )
    ),
    'domain tables must live only in their owning schema (coreapp.* / documents.documents)');

-- =============================================================================
-- 3. coreapp.customers (docs/db/README.md §4.1)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'customers', 'id', 'uuid', null, false),
    'coreapp.customers.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'customers', 'fiscal_code', 'character varying', 16, false),
    'coreapp.customers.fiscal_code must be varchar(16) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'customers', 'full_name', 'character varying', 200, false),
    'coreapp.customers.full_name must be varchar(200) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'customers', 'email', 'character varying', 255, true),
    'coreapp.customers.email must be varchar(255) NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'customers', 'created_at', 'timestamp with time zone', null, false),
    'coreapp.customers.created_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'customers', 'pk_customers') = array['id'],
    'pk_customers must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'customers', 'uk_customers_fiscal_code', 'u'),
    'uk_customers_fiscal_code unique constraint is missing (lookup path for customers)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'customers', 'ck_customers_fiscal_code_not_blank', 'c'),
    'ck_customers_fiscal_code_not_blank CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'customers', 'ck_customers_full_name_not_blank', 'c'),
    'ck_customers_full_name_not_blank CHECK is missing');

-- =============================================================================
-- 4. coreapp.policies (docs/db/README.md §4.2)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'policies', 'id', 'uuid', null, false),
    'coreapp.policies.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'policies', 'policy_number', 'character varying', 32, false),
    'coreapp.policies.policy_number must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'policies', 'customer_id', 'uuid', null, false),
    'coreapp.policies.customer_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'policies', 'product_code', 'character varying', 32, false),
    'coreapp.policies.product_code must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'policies', 'valid_from', 'date', null, false)
        and pg_temp.has_column('coreapp', 'policies', 'valid_to', 'date', null, false),
    'coreapp.policies.valid_from / valid_to must be date NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_numeric_column('coreapp', 'policies', 'premium', 12, 2),
    'coreapp.policies.premium must be numeric(12,2) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'policies', 'created_at', 'timestamp with time zone', null, false),
    'coreapp.policies.created_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'policies', 'pk_policies') = array['id'],
    'pk_policies must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'policies', 'uk_policies_policy_number', 'u'),
    'uk_policies_policy_number unique constraint is missing (POST /api/v1/claims lookup path)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'policies', 'fk_policies_customer', 'f'),
    'fk_policies_customer foreign key to coreapp.customers(id) is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'policies', 'ck_policies_validity_period', 'c'),
    'ck_policies_validity_period (valid_to > valid_from) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'policies', 'ck_policies_premium_non_negative', 'c'),
    'ck_policies_premium_non_negative (premium >= 0) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'policies', 'idx_policies_customer'),
    'index idx_policies_customer (customer_id) is missing');

-- =============================================================================
-- 5. coreapp.claims (docs/db/README.md §4.3)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'id', 'uuid', null, false),
    'coreapp.claims.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'claim_number', 'character varying', 32, false),
    'coreapp.claims.claim_number must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'policy_id', 'uuid', null, false),
    'coreapp.claims.policy_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'claim_type', 'character varying', 32, false),
    'coreapp.claims.claim_type must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'status', 'character varying', 32, false),
    'coreapp.claims.status must be varchar(32) NOT NULL (DD-7: varchar + CHECK, never a native enum)');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'description', 'character varying', 2000, true),
    'coreapp.claims.description must be varchar(2000) NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'incident_date', 'date', null, false),
    'coreapp.claims.incident_date must be date NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'opened_at', 'timestamp with time zone', null, false),
    'coreapp.claims.opened_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'updated_at', 'timestamp with time zone', null, false),
    'coreapp.claims.updated_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'version', 'bigint', null, false),
    'coreapp.claims.version must be bigint NOT NULL (Hibernate @Version optimistic locking)');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'claims', 'pk_claims') = array['id'],
    'pk_claims must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims', 'uk_claims_claim_number', 'u'),
    'uk_claims_claim_number unique constraint is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims', 'fk_claims_policy', 'f'),
    'fk_claims_policy foreign key to coreapp.policies(id) is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims', 'ck_claims_version_non_negative', 'c'),
    'ck_claims_version_non_negative (version >= 0) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims', 'ck_claims_updated_at_after_opened_at', 'c'),
    'ck_claims_updated_at_after_opened_at (updated_at >= opened_at) CHECK is missing');

-- §7.5 drift rule: the CHECK literals must be exactly the ClaimStatus enum values.
-- Adding/removing an enum constant therefore requires migration + this file + entity.
select pg_temp.assert_schema(
    pg_temp.check_literals('coreapp', 'claims', 'ck_claims_status')
        = array['APPRAISAL', 'APPROVED', 'ARCHIVED', 'CLOSED', 'DRAFT',
                'INTEGRATION_REQUESTED', 'REJECTED', 'SETTLEMENT', 'SUBMITTED',
                'UNDER_REVIEW'],
    'ck_claims_status must accept exactly the 10 ClaimStatus values '
    '(DRAFT, SUBMITTED, UNDER_REVIEW, INTEGRATION_REQUESTED, APPRAISAL, APPROVED, '
    'REJECTED, SETTLEMENT, CLOSED, ARCHIVED)');

-- DD-7: no native PostgreSQL ENUM type may be used for statuses (ALTER TYPE would lock).
select pg_temp.assert_schema(
    not exists (
        select 1
          from pg_type t
          join pg_namespace n on n.oid = t.typnamespace
         where t.typtype = 'e'
           and n.nspname in ('coreapp', 'documents')
    ),
    'no native PostgreSQL ENUM type may exist in coreapp/documents (DD-7: varchar + CHECK)');

-- Indexes and the query each one serves (docs/db/README.md §4.3).
select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claims', 'idx_claims_status'),
    'index idx_claims_status is missing (GET /api/v1/claims?status=, dashboard counts)');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claims', 'idx_claims_policy'),
    'index idx_claims_policy (policy_id) is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claims', 'idx_claims_opened_at', '%desc%'),
    'index idx_claims_opened_at must exist with DESC ordering (default list sort openedAt,desc)');

-- =============================================================================
-- 6. coreapp.claim_status_history (docs/db/README.md §4.4)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'id', 'uuid', null, false),
    'coreapp.claim_status_history.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'claim_id', 'uuid', null, false),
    'coreapp.claim_status_history.claim_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'from_status',
                       'character varying', 32, true),
    'coreapp.claim_status_history.from_status must be varchar(32) NULL (NULL = opening transition)');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'to_status',
                       'character varying', 32, false),
    'coreapp.claim_status_history.to_status must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'note', 'character varying', 500, true),
    'coreapp.claim_status_history.note must be varchar(500) NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'changed_at',
                       'timestamp with time zone', null, false),
    'coreapp.claim_status_history.changed_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'claim_status_history', 'pk_claim_status_history') = array['id'],
    'pk_claim_status_history must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'claim_status_history',
                           'fk_claim_status_history_claim') = 'cascade',
    'fk_claim_status_history_claim must be ON DELETE CASCADE (history dies with the claim)');

select pg_temp.assert_schema(
    pg_temp.check_literals('coreapp', 'claim_status_history', 'ck_claim_status_history_from_status')
        = array['APPRAISAL', 'APPROVED', 'ARCHIVED', 'CLOSED', 'DRAFT',
                'INTEGRATION_REQUESTED', 'REJECTED', 'SETTLEMENT', 'SUBMITTED',
                'UNDER_REVIEW'],
    'ck_claim_status_history_from_status must accept exactly the 10 ClaimStatus values');

select pg_temp.assert_schema(
    pg_temp.check_literals('coreapp', 'claim_status_history', 'ck_claim_status_history_to_status')
        = array['APPRAISAL', 'APPROVED', 'ARCHIVED', 'CLOSED', 'DRAFT',
                'INTEGRATION_REQUESTED', 'REJECTED', 'SETTLEMENT', 'SUBMITTED',
                'UNDER_REVIEW'],
    'ck_claim_status_history_to_status must accept exactly the 10 ClaimStatus values');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claim_status_history',
                           'ck_claim_status_history_actual_change', 'c'),
    'ck_claim_status_history_actual_change (from_status <> to_status) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claim_status_history', 'idx_claim_status_history_claim'),
    'index idx_claim_status_history_claim (claim_id, changed_at desc) is missing');

-- =============================================================================
-- 7. coreapp.claim_documents (docs/db/README.md §4.5)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_documents', 'claim_id', 'uuid', null, false),
    'coreapp.claim_documents.claim_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_documents', 'document_id', 'uuid', null, false),
    'coreapp.claim_documents.document_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_documents', 'linked_at',
                       'timestamp with time zone', null, false),
    'coreapp.claim_documents.linked_at must be timestamptz NOT NULL');

-- DD-4: natural composite key prevents duplicate attachments.
select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'claim_documents', 'pk_claim_documents')
        = array['claim_id', 'document_id'],
    'pk_claim_documents must be the composite primary key (claim_id, document_id)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'claim_documents', 'fk_claim_documents_claim') = 'cascade',
    'fk_claim_documents_claim must be ON DELETE CASCADE');

-- DD-5: document_id is a LOGICAL reference to document-service — no FK, ever.
select pg_temp.assert_schema(
    not exists (
        select 1
          from pg_constraint con
          join pg_class rel on rel.oid = con.conrelid
          join pg_namespace nsp on nsp.oid = rel.relnamespace
          join pg_attribute att on att.attrelid = con.conrelid and att.attnum = any (con.conkey)
         where nsp.nspname = 'coreapp'
           and rel.relname = 'claim_documents'
           and con.contype = 'f'
           and att.attname = 'document_id'
    ),
    'claim_documents.document_id must NOT have a foreign key (cross-service reference, DD-5)');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claim_documents', 'idx_claim_documents_document'),
    'index idx_claim_documents_document (reverse lookup by document_id) is missing');

-- =============================================================================
-- 8. documents.documents (docs/db/README.md §4.6)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'id', 'uuid', null, false),
    'documents.documents.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'claim_id', 'uuid', null, false),
    'documents.documents.claim_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'file_name', 'character varying', 255, false),
    'documents.documents.file_name must be varchar(255) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'content_type', 'character varying', 128, false),
    'documents.documents.content_type must be varchar(128) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'size_bytes', 'bigint', null, false),
    'documents.documents.size_bytes must be bigint NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'document_type', 'character varying', 32, false),
    'documents.documents.document_type must be varchar(32) NOT NULL');

-- DD-2: varchar(64), NOT char(64) — bpchar is not type-compatible with the
-- Hibernate String mapping under ddl-auto=validate (startup failure).
select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'checksum_sha256', 'character varying', 64, false),
    'documents.documents.checksum_sha256 must be varchar(64) NOT NULL (DD-2: not char(64))');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'storage_key', 'character varying', 512, false),
    'documents.documents.storage_key must be varchar(512) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'version', 'integer', null, false),
    'documents.documents.version must be integer NOT NULL (Hibernate @Version)');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'uploaded_at',
                       'timestamp with time zone', null, false),
    'documents.documents.uploaded_at must be timestamptz NOT NULL');

-- DD-6: soft delete -> deleted_at nullable.
select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'deleted_at',
                       'timestamp with time zone', null, true),
    'documents.documents.deleted_at must be timestamptz NULL (DD-6 soft delete)');

select pg_temp.assert_schema(
    pg_temp.key_columns('documents', 'documents', 'pk_documents') = array['id'],
    'pk_documents must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'uk_documents_storage_key', 'u'),
    'uk_documents_storage_key unique constraint is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_size_bytes_non_negative', 'c'),
    'ck_documents_size_bytes_non_negative (size_bytes >= 0) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_checksum_sha256_hex', 'c',
                           '%0-9a-f%'),
    'ck_documents_checksum_sha256_hex must enforce the 64-char hex format (DD-2)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_file_name_not_blank', 'c'),
    'ck_documents_file_name_not_blank CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_storage_key_not_blank', 'c'),
    'ck_documents_storage_key_not_blank CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents',
                           'ck_documents_deleted_at_after_uploaded_at', 'c'),
    'ck_documents_deleted_at_after_uploaded_at CHECK is missing');

-- DD-5: claim_id is a LOGICAL reference to core-app — no cross-service FK, ever.
select pg_temp.assert_schema(
    not exists (
        select 1
          from pg_constraint con
          join pg_class rel on rel.oid = con.conrelid
          join pg_namespace nsp on nsp.oid = rel.relnamespace
          join pg_attribute att on att.attrelid = con.conrelid and att.attnum = any (con.conkey)
         where nsp.nspname = 'documents'
           and rel.relname = 'documents'
           and con.contype = 'f'
           and att.attname = 'claim_id'
    ),
    'documents.documents.claim_id must NOT have a foreign key (cross-service reference, DD-5); '
    'fk_documents_supersedes is intra-schema and therefore allowed');

select pg_temp.assert_schema(
    pg_temp.has_index('documents', 'documents', 'idx_documents_claim'),
    'index idx_documents_claim (claim_id) is missing');

-- DD-6: the only hot path serves live documents -> the partial index must stay partial.
select pg_temp.assert_schema(
    pg_temp.has_index('documents', 'documents', 'idx_documents_claim_active',
                      '%where%deleted_at is null%'),
    'index idx_documents_claim_active must exist as PARTIAL (where deleted_at is null), DD-6');

select pg_temp.assert_schema(
    pg_temp.has_index('documents', 'documents', 'idx_documents_uploaded_at', '%desc%'),
    'index idx_documents_uploaded_at must exist with DESC ordering (latest uploads)');

-- =============================================================================
-- 9. coreapp.claims — estensioni TK-64FBC8 (docs/db/README.md §4.3)
--    Date di chiusura/archiviazione e coerenza con la macchina a stati.
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'closed_at', 'timestamp with time zone', null, true),
    'coreapp.claims.closed_at must be timestamptz NULL (chiusa)');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claims', 'archived_at', 'timestamp with time zone', null, true),
    'coreapp.claims.archived_at must be timestamptz NULL (archiviata)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims',
                           'ck_claims_closed_at_requires_closed_status', 'c'),
    'ck_claims_closed_at_requires_closed_status (closed_at only for CLOSED/ARCHIVED) is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims',
                           'ck_claims_archived_at_requires_archived_status', 'c'),
    'ck_claims_archived_at_requires_archived_status (archived_at only for ARCHIVED) is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claims', 'ck_claims_closed_at_after_opened_at', 'c'),
    'ck_claims_closed_at_after_opened_at (closed_at >= opened_at) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claims', 'idx_claims_closed_at', '%where%closed_at is not null%'),
    'index idx_claims_closed_at must exist as PARTIAL (where closed_at is not null)');

-- =============================================================================
-- 10. coreapp.claim_status_history — autore + coppia di transizione (A3)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_history', 'actor_id', 'uuid', null, true),
    'coreapp.claim_status_history.actor_id must be uuid NULL (historical rows have no actor)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'claim_status_history',
                           'fk_claim_status_history_actor') = 'set null',
    'fk_claim_status_history_actor must be ON DELETE SET NULL (the history survives the actor)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claim_status_history',
                           'fk_claim_status_history_transition', 'f',
                           '%foreign key (from_status, to_status)%'),
    'fk_claim_status_history_transition must be the composite FK (from_status, to_status) to '
    'coreapp.claim_status_transition (state machine enforced by the database, A3)');

-- =============================================================================
-- 11. coreapp.claim_documents — soft un-link (docs/db/README.md §4.5)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_documents', 'linked_by_actor_id', 'uuid', null, true),
    'coreapp.claim_documents.linked_by_actor_id must be uuid NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_documents', 'unlinked_at',
                       'timestamp with time zone', null, true),
    'coreapp.claim_documents.unlinked_at must be timestamptz NULL (soft un-link)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'claim_documents',
                           'fk_claim_documents_linked_by_actor') = 'set null',
    'fk_claim_documents_linked_by_actor must be ON DELETE SET NULL');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claim_documents',
                           'ck_claim_documents_unlinked_after_linked', 'c'),
    'ck_claim_documents_unlinked_after_linked (unlinked_at >= linked_at) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claim_documents', 'idx_claim_documents_active',
                      '%where%unlinked_at is null%'),
    'index idx_claim_documents_active must exist as PARTIAL (where unlinked_at is null)');

-- =============================================================================
-- 12. coreapp.roles — lookup dei ruoli (reference data, seeded)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'roles', 'code', 'character varying', 32, false),
    'coreapp.roles.code must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'roles', 'name', 'character varying', 100, false),
    'coreapp.roles.name must be varchar(100) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'roles', 'description', 'character varying', 500, true),
    'coreapp.roles.description must be varchar(500) NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'roles', 'created_at', 'timestamp with time zone', null, false),
    'coreapp.roles.created_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'roles', 'pk_roles') = array['code'],
    'pk_roles must be the business key (code): roles are identified by code everywhere');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'roles', 'ck_roles_code_format', 'c'),
    'ck_roles_code_format (^[A-Z][A-Z0-9_]{2,31}$) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'roles', 'ck_roles_name_not_blank', 'c'),
    'ck_roles_name_not_blank CHECK is missing');

select pg_temp.assert_schema(
    (select array_agg(r.code::text order by r.code::text) from coreapp.roles r)
        = array['ADMIN', 'APPRAISER', 'AUDITOR', 'CLAIMS_OFFICER', 'SETTLEMENT_OFFICER'],
    'coreapp.roles must be seeded with the 5 reference roles '
    '(CLAIMS_OFFICER, APPRAISER, SETTLEMENT_OFFICER, AUDITOR, ADMIN)');

-- =============================================================================
-- 13. coreapp.actors — attori / utenti (docs/db/README.md §4.7)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'id', 'uuid', null, false),
    'coreapp.actors.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'subject_ref', 'character varying', 128, false),
    'coreapp.actors.subject_ref must be varchar(128) NOT NULL (external IdP subject)');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'display_name', 'character varying', 200, false),
    'coreapp.actors.display_name must be varchar(200) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'email', 'character varying', 255, true),
    'coreapp.actors.email must be varchar(255) NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'actor_type', 'character varying', 32, false),
    'coreapp.actors.actor_type must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'enabled', 'boolean', null, false),
    'coreapp.actors.enabled must be boolean NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'created_at', 'timestamp with time zone', null, false)
        and pg_temp.has_column('coreapp', 'actors', 'updated_at',
                               'timestamp with time zone', null, false),
    'coreapp.actors.created_at / updated_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'version', 'bigint', null, false),
    'coreapp.actors.version must be bigint NOT NULL (Hibernate @Version)');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actors', 'deleted_at', 'timestamp with time zone', null, true),
    'coreapp.actors.deleted_at must be timestamptz NULL (DD-6 soft delete)');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'actors', 'pk_actors') = array['id'],
    'pk_actors must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actors', 'uk_actors_subject_ref', 'u'),
    'uk_actors_subject_ref unique constraint is missing (identity is reactivated, never re-created)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actors', 'ck_actors_subject_ref_format', 'c'),
    'ck_actors_subject_ref_format CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actors', 'ck_actors_actor_type', 'c',
                           '%INTERNAL%EXTERNAL%SYSTEM%'),
    'ck_actors_actor_type must accept exactly INTERNAL / EXTERNAL / SYSTEM');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actors', 'ck_actors_version_non_negative', 'c'),
    'ck_actors_version_non_negative (version >= 0) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actors', 'ck_actors_deleted_at_after_created_at', 'c'),
    'ck_actors_deleted_at_after_created_at CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'actors', 'idx_actors_type_enabled',
                      '%where%deleted_at is null%'),
    'index idx_actors_type_enabled must exist as PARTIAL (live enabled actors)');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'actors', 'idx_actors_display_name', '%lower%'),
    'index idx_actors_display_name must be on lower(display_name) (case-insensitive search)');

-- =============================================================================
-- 14. coreapp.actor_roles — ruoli concessi a un attore
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actor_roles', 'actor_id', 'uuid', null, false),
    'coreapp.actor_roles.actor_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actor_roles', 'role_code', 'character varying', 32, false),
    'coreapp.actor_roles.role_code must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actor_roles', 'granted_at',
                       'timestamp with time zone', null, false),
    'coreapp.actor_roles.granted_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actor_roles', 'granted_by', 'uuid', null, true),
    'coreapp.actor_roles.granted_by must be uuid NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'actor_roles', 'revoked_at',
                       'timestamp with time zone', null, true),
    'coreapp.actor_roles.revoked_at must be timestamptz NULL (revoke, never delete)');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'actor_roles', 'pk_actor_roles')
        = array['actor_id', 'role_code'],
    'pk_actor_roles must be the composite primary key (actor_id, role_code)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'actor_roles', 'fk_actor_roles_actor') = 'cascade',
    'fk_actor_roles_actor must be ON DELETE CASCADE');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actor_roles', 'fk_actor_roles_role', 'f'),
    'fk_actor_roles_role foreign key to coreapp.roles(code) is missing');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'actor_roles', 'fk_actor_roles_granted_by') = 'set null',
    'fk_actor_roles_granted_by must be ON DELETE SET NULL');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'actor_roles', 'ck_actor_roles_revoked_after_granted', 'c'),
    'ck_actor_roles_revoked_after_granted CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'actor_roles', 'idx_actor_roles_role',
                      '%where%revoked_at is null%'),
    'index idx_actor_roles_role must exist as PARTIAL (active grants only)');

-- =============================================================================
-- 15. coreapp.claim_assignments — attore + ruolo su una pratica (DD-15)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_assignments', 'id', 'uuid', null, false),
    'coreapp.claim_assignments.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_assignments', 'claim_id', 'uuid', null, false)
        and pg_temp.has_column('coreapp', 'claim_assignments', 'actor_id', 'uuid', null, false),
    'coreapp.claim_assignments.claim_id / actor_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_assignments', 'role_code',
                       'character varying', 32, false),
    'coreapp.claim_assignments.role_code must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_assignments', 'assigned_at',
                       'timestamp with time zone', null, false),
    'coreapp.claim_assignments.assigned_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_assignments', 'assigned_by', 'uuid', null, true)
        and pg_temp.has_column('coreapp', 'claim_assignments', 'unassigned_at',
                               'timestamp with time zone', null, true),
    'coreapp.claim_assignments.assigned_by / unassigned_at must be NULLABLE');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'claim_assignments', 'pk_claim_assignments') = array['id'],
    'pk_claim_assignments must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'claim_assignments',
                           'fk_claim_assignments_claim') = 'cascade',
    'fk_claim_assignments_claim must be ON DELETE CASCADE');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claim_assignments', 'fk_claim_assignments_role', 'f')
        and pg_temp.has_constraint('coreapp', 'claim_assignments',
                                   'fk_claim_assignments_actor', 'f'),
    'fk_claim_assignments_actor / fk_claim_assignments_role are missing');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'claim_assignments',
                           'fk_claim_assignments_assigned_by') = 'set null',
    'fk_claim_assignments_assigned_by must be ON DELETE SET NULL');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claim_assignments',
                           'ck_claim_assignments_unassigned_after_assigned', 'c'),
    'ck_claim_assignments_unassigned_after_assigned CHECK is missing');

-- DD-15: DB-enforced "at most one active holder per role per claim".
select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claim_assignments', 'uk_claim_assignments_active_role',
                      '%unique%where%unassigned_at is null%'),
    'uk_claim_assignments_active_role must exist as PARTIAL UNIQUE '
    '(claim_id, role_code) where unassigned_at is null (DD-15)');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claim_assignments', 'idx_claim_assignments_actor_active',
                      '%where%unassigned_at is null%'),
    'index idx_claim_assignments_actor_active must exist as PARTIAL ("my claims")');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'claim_assignments', 'idx_claim_assignments_claim'),
    'index idx_claim_assignments_claim (claim_id, assigned_at desc) is missing');

-- =============================================================================
-- 16. coreapp.audit_events — append-only audit trail (A5 / DD-11)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'id', 'uuid', null, false),
    'coreapp.audit_events.id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'claim_id', 'uuid', null, true)
        and pg_temp.has_column('coreapp', 'audit_events', 'actor_id', 'uuid', null, true),
    'coreapp.audit_events.claim_id / actor_id must be NULLABLE (non-claim events exist)');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'event_type', 'character varying', 64, false),
    'coreapp.audit_events.event_type must be varchar(64) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'entity_type', 'character varying', 32, false),
    'coreapp.audit_events.entity_type must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'entity_id', 'uuid', null, false),
    'coreapp.audit_events.entity_id must be uuid NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'outcome', 'character varying', 16, false),
    'coreapp.audit_events.outcome must be varchar(16) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'occurred_at',
                       'timestamp with time zone', null, false),
    'coreapp.audit_events.occurred_at must be timestamptz NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'request_id', 'character varying', 64, true),
    'coreapp.audit_events.request_id must be varchar(64) NULL (correlation id)');

-- DD-11 (plan risk R1): details is jsonb, mapped by Hibernate with SqlTypes.JSON.
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'audit_events', 'details', 'jsonb', null, true),
    'coreapp.audit_events.details must be jsonb NULL');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'audit_events', 'pk_audit_events') = array['id'],
    'pk_audit_events must be the single-column primary key (id)');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'audit_events', 'fk_audit_events_claim') = 'cascade',
    'fk_audit_events_claim must be ON DELETE CASCADE');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('coreapp', 'audit_events', 'fk_audit_events_actor') = 'set null',
    'fk_audit_events_actor must be ON DELETE SET NULL (the audit row survives the actor)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'audit_events', 'ck_audit_events_event_type_format', 'c'),
    'ck_audit_events_event_type_format CHECK is missing (format, not enumerated values: A5)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'audit_events', 'ck_audit_events_outcome', 'c',
                           '%SUCCESS%FAILURE%'),
    'ck_audit_events_outcome must accept exactly SUCCESS / FAILURE');

-- No enumerated CHECK on event_type: a new business event must not need a migration.
select pg_temp.assert_schema(
    not exists (
        select 1
          from pg_constraint con
          join pg_class rel on rel.oid = con.conrelid
          join pg_namespace nsp on nsp.oid = rel.relnamespace
         where nsp.nspname = 'coreapp'
           and rel.relname = 'audit_events'
           and con.contype = 'c'
           and pg_get_constraintdef(con.oid) ilike '%event_type in%'
    ),
    'audit_events.event_type must be validated by a regex FORMAT, never by an enumerated CHECK (A5)');

select pg_temp.assert_schema(
    pg_temp.has_index('coreapp', 'audit_events', 'idx_audit_events_claim')
        and pg_temp.has_index('coreapp', 'audit_events', 'idx_audit_events_entity')
        and pg_temp.has_index('coreapp', 'audit_events', 'idx_audit_events_actor')
        and pg_temp.has_index('coreapp', 'audit_events', 'idx_audit_events_occurred_at'),
    'the four audit_events indexes (claim, entity, actor, occurred_at) are missing');

-- =============================================================================
-- 17. coreapp.claim_status_transition — regole della macchina a stati (A3)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('coreapp', 'claim_status_transition', 'from_status',
                       'character varying', 32, false)
        and pg_temp.has_column('coreapp', 'claim_status_transition', 'to_status',
                               'character varying', 32, false),
    'coreapp.claim_status_transition.from_status / to_status must be varchar(32) NOT NULL');

select pg_temp.assert_schema(
    pg_temp.key_columns('coreapp', 'claim_status_transition', 'pk_claim_status_transition')
        = array['from_status', 'to_status'],
    'pk_claim_status_transition must be the composite primary key (from_status, to_status)');

select pg_temp.assert_schema(
    pg_temp.check_literals('coreapp', 'claim_status_transition', 'ck_claim_status_transition_from')
        = array['APPRAISAL', 'APPROVED', 'ARCHIVED', 'CLOSED', 'DRAFT',
                'INTEGRATION_REQUESTED', 'REJECTED', 'SETTLEMENT', 'SUBMITTED',
                'UNDER_REVIEW'],
    'ck_claim_status_transition_from must accept exactly the 10 ClaimStatus values');

select pg_temp.assert_schema(
    pg_temp.check_literals('coreapp', 'claim_status_transition', 'ck_claim_status_transition_to')
        = array['APPRAISAL', 'APPROVED', 'ARCHIVED', 'CLOSED', 'DRAFT',
                'INTEGRATION_REQUESTED', 'REJECTED', 'SETTLEMENT', 'SUBMITTED',
                'UNDER_REVIEW'],
    'ck_claim_status_transition_to must accept exactly the 10 ClaimStatus values');

select pg_temp.assert_schema(
    pg_temp.has_constraint('coreapp', 'claim_status_transition',
                           'ck_claim_status_transition_actual_change', 'c'),
    'ck_claim_status_transition_actual_change (from_status <> to_status) CHECK is missing');

-- Seed == ClaimStatus.allowedTransitions() (set equality, decisions A3/A10).
select pg_temp.assert_schema(
    (select array_agg(t.from_status::text || '->' || t.to_status::text
                      order by t.from_status::text || '->' || t.to_status::text)
       from coreapp.claim_status_transition t)
        = array['APPRAISAL->APPROVED', 'APPRAISAL->REJECTED', 'APPRAISAL->UNDER_REVIEW',
                'APPROVED->SETTLEMENT', 'CLOSED->ARCHIVED', 'DRAFT->SUBMITTED',
                'INTEGRATION_REQUESTED->UNDER_REVIEW', 'REJECTED->CLOSED',
                'SETTLEMENT->CLOSED', 'SUBMITTED->UNDER_REVIEW',
                'UNDER_REVIEW->APPRAISAL', 'UNDER_REVIEW->APPROVED',
                'UNDER_REVIEW->INTEGRATION_REQUESTED', 'UNDER_REVIEW->REJECTED'],
    'coreapp.claim_status_transition must contain exactly the 14 transitions of ClaimStatus '
    '(APPROVED -> CLOSED is NOT allowed: liquidazione cannot be skipped, decision A10)');

-- =============================================================================
-- 18. documents.documents — versioning, key_ref e retention (A6/A7)
-- =============================================================================
select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'document_key', 'uuid', null, false),
    'documents.documents.document_key must be uuid NOT NULL (logical identity, backfilled = id)');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'version_number', 'integer', null, false),
    'documents.documents.version_number must be integer NOT NULL (business version)');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'is_current', 'boolean', null, false),
    'documents.documents.is_current must be boolean NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'key_ref', 'character varying', 160, true),
    'documents.documents.key_ref must be varchar(160) NULL (opaque KMS reference)');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'retention_until', 'date', null, true),
    'documents.documents.retention_until must be date NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'legal_hold', 'boolean', null, false),
    'documents.documents.legal_hold must be boolean NOT NULL');

select pg_temp.assert_schema(
    pg_temp.has_column('documents', 'documents', 'supersedes_id', 'uuid', null, true),
    'documents.documents.supersedes_id must be uuid NULL (previous version)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_version_number_positive', 'c'),
    'ck_documents_version_number_positive (version_number >= 1) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'uk_documents_document_key_version', 'u'),
    'uk_documents_document_key_version unique (document_key, version_number) is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_key_ref_format', 'c'),
    'ck_documents_key_ref_format CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_retention_after_upload', 'c'),
    'ck_documents_retention_after_upload (retention_until >= upload date) CHECK is missing');

select pg_temp.assert_schema(
    pg_temp.fk_delete_rule('documents', 'documents', 'fk_documents_supersedes') = 'set null',
    'fk_documents_supersedes must be ON DELETE SET NULL');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'uk_documents_supersedes', 'u'),
    'uk_documents_supersedes unique (supersedes_id) is missing (linear chain, no forks)');

select pg_temp.assert_schema(
    pg_temp.has_constraint('documents', 'documents', 'ck_documents_supersedes_not_self', 'c'),
    'ck_documents_supersedes_not_self CHECK is missing');

-- DD-12: exactly one live current version per document_key.
select pg_temp.assert_schema(
    pg_temp.has_index('documents', 'documents', 'uk_documents_current_version',
                      '%unique%where%is_current%'),
    'uk_documents_current_version must exist as PARTIAL UNIQUE (document_key) '
    'where is_current and deleted_at is null (DD-12)');

select pg_temp.assert_schema(
    pg_temp.has_index('documents', 'documents', 'idx_documents_key_versions', '%version_number desc%'),
    'index idx_documents_key_versions (document_key, version_number desc) is missing');

select pg_temp.assert_schema(
    pg_temp.has_index('documents', 'documents', 'idx_documents_retention_due',
                      '%where%retention_until is not null%'),
    'index idx_documents_retention_due must exist as PARTIAL (purge job selector)');

-- =============================================================================
-- 19. Flyway bookkeeping — only when the schemas were migrated by Flyway
--    (skipped when the raw V1 scripts were applied with psql: no history table).
-- =============================================================================
do $$
begin
    if to_regclass('coreapp.flyway_schema_history') is not null then
        if exists (select 1 from coreapp.flyway_schema_history where success is not true) then
            raise exception
                'SCHEMA CONTRACT VIOLATION: coreapp.flyway_schema_history contains a failed migration';
        end if;
        if not exists (select 1
                         from coreapp.flyway_schema_history
                        where version = '1' and type = 'SQL' and success) then
            raise exception
                'SCHEMA CONTRACT VIOLATION: coreapp migration V1 is not recorded as successful';
        end if;
        if not exists (select 1
                         from coreapp.flyway_schema_history
                        where version = '2' and type = 'SQL' and success) then
            raise exception
                'SCHEMA CONTRACT VIOLATION: coreapp migration V2 is not recorded as successful';
        end if;
    end if;

    if to_regclass('documents.flyway_schema_history') is not null then
        if exists (select 1 from documents.flyway_schema_history where success is not true) then
            raise exception
                'SCHEMA CONTRACT VIOLATION: documents.flyway_schema_history contains a failed migration';
        end if;
        if not exists (select 1
                         from documents.flyway_schema_history
                        where version = '1' and type = 'SQL' and success) then
            raise exception
                'SCHEMA CONTRACT VIOLATION: documents migration V1 is not recorded as successful';
        end if;
        if not exists (select 1
                         from documents.flyway_schema_history
                        where version = '2' and type = 'SQL' and success) then
            raise exception
                'SCHEMA CONTRACT VIOLATION: documents migration V2 is not recorded as successful';
        end if;
    end if;
end;
$$;

-- =============================================================================
-- 20. Summary row — only printed when every assertion above passed.
-- =============================================================================
select 'SCHEMA CONTRACT OK' as result,
       200 as assertions_passed,
       '12 tables · 2 schemas · 77 named constraints (12 PK, 7 UK, 17 FK, 41 CHECK) '
       '· 24 indexes · 10 ClaimStatus values · 14 transition rules' as checked,
       'coreapp + documents match docs/db/README.md §4' as source_of_truth;
