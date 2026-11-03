-- =============================================================================
-- TK-DE445B — core-app persistence schema (PostgreSQL 16, schema: coreapp)
-- -----------------------------------------------------------------------------
-- Module      : core-app (:8180)          Strategy: database-per-service
-- Tool        : Flyway 10 (changelog by version, forward-only, IMMUTABLE)
-- Owner       : it.enterprise.claims.core
-- Applied via : spring.flyway.locations=classpath:db/migration
--               spring.flyway.schemas=coreapp
--               spring.flyway.default-schema=coreapp
--
-- RULES
--  * NEVER edit this file after it has been applied to any environment.
--    Any schema change requires a new V<n>__<snake_case_description>.sql.
--  * All objects are fully qualified with the "coreapp" schema, so the migration
--    is correct regardless of the session search_path.
--  * UUID primary keys are generated in the application layer (no DB default),
--    as stated in the architecture blueprint (section 2.6).
--  * No data migration and no seed data in this phase (blueprint DB_SKIP note).
--  * "version" columns are Hibernate @Version optimistic-locking counters.
-- =============================================================================

create schema if not exists coreapp;

-- -----------------------------------------------------------------------------
-- customers — anagrafica contraente/assicurato (read model for policies)
-- -----------------------------------------------------------------------------
create table coreapp.customers
(
    id          uuid         not null,
    fiscal_code varchar(16)  not null,
    full_name   varchar(200) not null,
    email       varchar(255),
    created_at  timestamptz  not null default now(),
    constraint pk_customers primary key (id),
    constraint uk_customers_fiscal_code unique (fiscal_code),
    constraint ck_customers_fiscal_code_not_blank check (length(btrim(fiscal_code)) > 0),
    constraint ck_customers_full_name_not_blank check (length(btrim(full_name)) > 0)
);

comment on table coreapp.customers is
    'Customers (contraenti/assicurati) referenced by policies.';


-- -----------------------------------------------------------------------------
-- policies — polizze assicurative
-- -----------------------------------------------------------------------------
create table coreapp.policies
(
    id            uuid          not null,
    policy_number varchar(32)   not null,
    customer_id   uuid          not null,
    product_code  varchar(32)   not null,
    valid_from    date          not null,
    valid_to      date          not null,
    premium       numeric(12,2) not null,
    created_at    timestamptz   not null default now(),
    constraint pk_policies primary key (id),
    constraint uk_policies_policy_number unique (policy_number),
    constraint fk_policies_customer foreign key (customer_id) references coreapp.customers (id),
    constraint ck_policies_validity_period check (valid_to > valid_from),
    constraint ck_policies_premium_non_negative check (premium >= 0)
);

create index idx_policies_customer on coreapp.policies (customer_id);

comment on table coreapp.policies is
    'Insurance policies; claims always reference exactly one policy.';


-- -----------------------------------------------------------------------------
-- claims — pratiche assicurative (aggregate root)
-- Note: "status" mirrors it.enterprise.claims.core.domain.model.ClaimStatus.
-- -----------------------------------------------------------------------------
create table coreapp.claims
(
    id            uuid          not null,
    claim_number  varchar(32)   not null,
    policy_id     uuid          not null,
    claim_type    varchar(32)   not null,
    status        varchar(32)   not null,
    description   varchar(2000),
    incident_date date          not null,
    opened_at     timestamptz   not null default now(),
    updated_at    timestamptz   not null default now(),
    version       bigint        not null default 0,
    constraint pk_claims primary key (id),
    constraint uk_claims_claim_number unique (claim_number),
    constraint fk_claims_policy foreign key (policy_id) references coreapp.policies (id),
    constraint ck_claims_status check (status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPROVED',
        'REJECTED',
        'CLOSED'
    )),
    constraint ck_claims_updated_at_after_opened_at check (updated_at >= opened_at),
    constraint ck_claims_version_non_negative check (version >= 0)
);

-- Listing/filtering by status (GET /api/v1/claims?status=...)
create index idx_claims_status on coreapp.claims (status);
-- Join/filter by policy (GET /api/v1/policies/{id} drill-down)
create index idx_claims_policy on coreapp.claims (policy_id);
-- Default page sort of the claims list endpoint: sort=openedAt,desc
create index idx_claims_opened_at on coreapp.claims (opened_at desc);

comment on table coreapp.claims is
    'Insurance claims. status is enforced by ck_claims_status and by the ClaimStatus state machine.';
comment on column coreapp.claims.version is
    'Optimistic locking counter managed by Hibernate (@Version).';
comment on column coreapp.claims.description is
    'Free-text claim description, max 2000 chars (max api payload is validated at the web layer).';


-- -----------------------------------------------------------------------------
-- claim_status_history — audit trail of every status transition
-- from_status is nullable: the first row (claim opening) has no previous status.
-- -----------------------------------------------------------------------------
create table coreapp.claim_status_history
(
    id          uuid        not null,
    claim_id    uuid        not null,
    from_status varchar(32),
    to_status   varchar(32) not null,
    note        varchar(500),
    changed_at  timestamptz not null default now(),
    constraint pk_claim_status_history primary key (id),
    constraint fk_claim_status_history_claim foreign key (claim_id)
        references coreapp.claims (id) on delete cascade,
    constraint ck_claim_status_history_from_status check (from_status is null or from_status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPROVED',
        'REJECTED',
        'CLOSED'
    )),
    constraint ck_claim_status_history_to_status check (to_status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPROVED',
        'REJECTED',
        'CLOSED'
    )),
    constraint ck_claim_status_history_actual_change check (from_status is null or from_status <> to_status)
);

-- GET /api/v1/claims/{id}/status-history -> ordered by changed_at asc within a claim
create index idx_claim_status_history_claim on coreapp.claim_status_history (claim_id, changed_at desc);

comment on table coreapp.claim_status_history is
    'Append-only audit trail of claim status transitions.';


-- -----------------------------------------------------------------------------
-- claim_documents — link table between a claim and document-service document ids.
-- document_id is a LOGICAL reference to the documents service (no cross-service
-- FK by design: database-per-service).
-- -----------------------------------------------------------------------------
create table coreapp.claim_documents
(
    claim_id    uuid        not null,
    document_id uuid        not null,
    linked_at   timestamptz not null default now(),
    constraint pk_claim_documents primary key (claim_id, document_id),
    constraint fk_claim_documents_claim foreign key (claim_id)
        references coreapp.claims (id) on delete cascade
);

-- Reverse lookup: which claim owns a given document id
create index idx_claim_documents_document on coreapp.claim_documents (document_id);

comment on table coreapp.claim_documents is
    'Attaches document-service document ids to a claim (logical, no cross-service FK).';
