-- =============================================================================
-- TK-64FBC8 — core-app: attori, ruoli, assegnazioni, audit trail e macchina a
-- stati della pratica applicata A LIVELLO DI DATABASE (schema: coreapp)
-- -----------------------------------------------------------------------------
-- Module      : core-app (:8180)          Strategy: database-per-service
-- Tool        : Flyway 10 (changelog by version, forward-only)
-- Owner       : it.enterprise.claims.core
-- Applied via : spring.flyway.locations=classpath:db/migration
--               spring.flyway.schemas=coreapp
--               spring.flyway.default-schema=coreapp
--
-- RULES (docs/db/README.md §7)
--  * V1__init_claims.sql is IMMUTABLE: every change of TK-64FBC8 lands here.
--  * This migration is IDEMPOTENT and applicable FROM ZERO together with V1:
--    DDL is guarded (`create table if not exists`, `add column if not exists`,
--    `drop constraint if exists` + `add constraint`, `create index if not
--    exists`, `insert ... on conflict ... do nothing`). Re-running it on an
--    already migrated database is a no-op.
--  * All objects are fully qualified with the "coreapp" schema (DD-9), so the
--    migration is correct regardless of the session search_path.
--  * UUID primary keys are generated in the application layer (DD-1): no DB
--    default, no extension dependency.
--  * No DB triggers (DD-8): timestamps are maintained by JPA lifecycle
--    callbacks; the column defaults are only safety nets for manual SQL.
--  * Reference data (roles, claim_status_transition) is the ONLY seed allowed
--    in this phase: it is required for FK integrity and for the state machine.
--    No business/demo rows.
--
-- ORDER MATTERS: referenced tables are created before the FKs that point at
-- them (roles → actors → actor_roles/claim_assignments → audit_events →
-- claim_status_transition → alterations of the V1 tables).
-- =============================================================================

create schema if not exists coreapp;

-- -----------------------------------------------------------------------------
-- 1. roles — lookup dei ruoli (Istruttore, Perito, Liquidatore, ...)
-- -----------------------------------------------------------------------------
create table if not exists coreapp.roles
(
    code        varchar(32)  not null,
    name        varchar(100) not null,
    description varchar(500),
    created_at  timestamptz  not null default now(),
    constraint pk_roles primary key (code),
    constraint ck_roles_code_format check (code ~ '^[A-Z][A-Z0-9_]{2,31}$'),
    constraint ck_roles_name_not_blank check (length(btrim(name)) > 0)
);

comment on table coreapp.roles is
    'Ruoli applicativi (reference data, seeded here because the FKs depend on it).';

insert into coreapp.roles (code, name, description)
values ('CLAIMS_OFFICER', 'Istruttore', 'Gestisce l istruttoria della pratica'),
       ('APPRAISER', 'Perito', 'Esegue la perizia e ne registra l esito'),
       ('SETTLEMENT_OFFICER', 'Liquidatore', 'Gestisce la liquidazione del sinistro'),
       ('AUDITOR', 'Revisore', 'Consulta pratiche e audit trail in sola lettura'),
       ('ADMIN', 'Amministratore', 'Amministra attori, ruoli e configurazione')
on conflict (code) do nothing;


-- -----------------------------------------------------------------------------
-- 2. actors — attori / utenti che operano sulle pratiche
-- subject_ref is the external IdP subject (opaque, caller-asserted in fase 1).
-- It stays UNIQUE even after the soft delete: an identity is reactivated,
-- never re-created (documented in docs/db/README.md §4.7).
-- -----------------------------------------------------------------------------
create table if not exists coreapp.actors
(
    id           uuid         not null,
    subject_ref  varchar(128) not null,
    display_name varchar(200) not null,
    email        varchar(255),
    actor_type   varchar(32)  not null,
    enabled      boolean      not null default true,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    version      bigint       not null default 0,
    deleted_at   timestamptz,
    constraint pk_actors primary key (id),
    constraint uk_actors_subject_ref unique (subject_ref),
    constraint ck_actors_subject_ref_format
        check (subject_ref ~ '^[A-Za-z0-9._:@|/-]{3,128}$'),
    constraint ck_actors_display_name_not_blank check (length(btrim(display_name)) > 0),
    constraint ck_actors_email_format
        check (email is null or email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    constraint ck_actors_actor_type check (actor_type in ('INTERNAL', 'EXTERNAL', 'SYSTEM')),
    constraint ck_actors_updated_at_after_created_at check (updated_at >= created_at),
    constraint ck_actors_version_non_negative check (version >= 0),
    constraint ck_actors_deleted_at_after_created_at
        check (deleted_at is null or deleted_at >= created_at)
);

-- Hot path: "chi può lavorare adesso" (attori attivi, non cancellati).
create index if not exists idx_actors_type_enabled
    on coreapp.actors (actor_type) where deleted_at is null and enabled = true;
-- Case-insensitive lookup by display name (search box).
create index if not exists idx_actors_display_name on coreapp.actors (lower(display_name));

comment on table coreapp.actors is
    'Attori/utenti. Soft delete (deleted_at): every read path must filter deleted_at is null.';
comment on column coreapp.actors.subject_ref is
    'External IdP subject (ADR-0005). Caller-asserted in phase 1, unique forever.';
comment on column coreapp.actors.version is
    'Optimistic locking counter managed by Hibernate (@Version).';


-- -----------------------------------------------------------------------------
-- 3. actor_roles — ruolo assegnato a un attore (revoke = revoked_at valorizzato)
-- -----------------------------------------------------------------------------
create table if not exists coreapp.actor_roles
(
    actor_id   uuid        not null,
    role_code  varchar(32) not null,
    granted_at timestamptz not null default now(),
    granted_by uuid,
    revoked_at timestamptz,
    constraint pk_actor_roles primary key (actor_id, role_code),
    constraint fk_actor_roles_actor foreign key (actor_id)
        references coreapp.actors (id) on delete cascade,
    constraint fk_actor_roles_role foreign key (role_code)
        references coreapp.roles (code),
    constraint fk_actor_roles_granted_by foreign key (granted_by)
        references coreapp.actors (id) on delete set null,
    constraint ck_actor_roles_revoked_after_granted
        check (revoked_at is null or revoked_at >= granted_at)
);

create index if not exists idx_actor_roles_role
    on coreapp.actor_roles (role_code) where revoked_at is null;

comment on table coreapp.actor_roles is
    'Ruoli concessi a un attore. Revoca = revoked_at valorizzato (nessun delete).';


-- -----------------------------------------------------------------------------
-- 4. claim_assignments — attore + ruolo su una pratica
-- At most ONE active holder per (claim, role): enforced by the partial unique
-- index uk_claim_assignments_active_role, not by the application.
-- -----------------------------------------------------------------------------
create table if not exists coreapp.claim_assignments
(
    id            uuid        not null,
    claim_id      uuid        not null,
    actor_id      uuid        not null,
    role_code     varchar(32) not null,
    assigned_at   timestamptz not null default now(),
    assigned_by   uuid,
    unassigned_at timestamptz,
    constraint pk_claim_assignments primary key (id),
    constraint fk_claim_assignments_claim foreign key (claim_id)
        references coreapp.claims (id) on delete cascade,
    constraint fk_claim_assignments_actor foreign key (actor_id)
        references coreapp.actors (id),
    constraint fk_claim_assignments_role foreign key (role_code)
        references coreapp.roles (code),
    constraint fk_claim_assignments_assigned_by foreign key (assigned_by)
        references coreapp.actors (id) on delete set null,
    constraint ck_claim_assignments_unassigned_after_assigned
        check (unassigned_at is null or unassigned_at >= assigned_at)
);

-- Al massimo un titolare attivo per ruolo su una pratica.
create unique index if not exists uk_claim_assignments_active_role
    on coreapp.claim_assignments (claim_id, role_code) where unassigned_at is null;
-- "Le mie pratiche": assegnazioni attive di un attore, più recenti prima.
create index if not exists idx_claim_assignments_actor_active
    on coreapp.claim_assignments (actor_id, assigned_at desc) where unassigned_at is null;
-- GET /api/v1/claims/{claimId}/assignments (storico completo).
create index if not exists idx_claim_assignments_claim
    on coreapp.claim_assignments (claim_id, assigned_at desc);

comment on table coreapp.claim_assignments is
    'Assegnazione attore+ruolo a una pratica. Un-assign = unassigned_at (soft).';


-- -----------------------------------------------------------------------------
-- 5. audit_events — audit trail generico, APPEND-ONLY
-- event_type / entity_type are validated by FORMAT (regex), never by an
-- enumerated CHECK: a new business event must not require a migration (A5).
-- There is intentionally NO trigger enforcing immutability (DD-8): the write
-- path is restricted in the ORM (@Immutable, updatable = false) and in the
-- repository port (append + finders only).
-- -----------------------------------------------------------------------------
create table if not exists coreapp.audit_events
(
    id          uuid        not null,
    claim_id    uuid,
    actor_id    uuid,
    event_type  varchar(64) not null,
    entity_type varchar(32) not null,
    entity_id   uuid        not null,
    outcome     varchar(16) not null default 'SUCCESS',
    occurred_at timestamptz not null default now(),
    request_id  varchar(64),
    details     jsonb,
    constraint pk_audit_events primary key (id),
    constraint fk_audit_events_claim foreign key (claim_id)
        references coreapp.claims (id) on delete cascade,
    constraint fk_audit_events_actor foreign key (actor_id)
        references coreapp.actors (id) on delete set null,
    constraint ck_audit_events_event_type_format check (event_type ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    constraint ck_audit_events_entity_type_format check (entity_type ~ '^[A-Z][A-Z0-9_]{2,31}$'),
    constraint ck_audit_events_outcome check (outcome in ('SUCCESS', 'FAILURE')),
    constraint ck_audit_events_request_id_format
        check (request_id is null or request_id ~ '^[A-Za-z0-9._:-]{1,64}$')
);

create index if not exists idx_audit_events_claim
    on coreapp.audit_events (claim_id, occurred_at desc);
-- (entity_type, entity_id) is polymorphic by design: no FK, index only.
create index if not exists idx_audit_events_entity
    on coreapp.audit_events (entity_type, entity_id, occurred_at desc);
create index if not exists idx_audit_events_actor
    on coreapp.audit_events (actor_id, occurred_at desc);
create index if not exists idx_audit_events_occurred_at
    on coreapp.audit_events (occurred_at desc);

comment on table coreapp.audit_events is
    'Append-only audit trail. No UPDATE/DELETE path in the code, no trigger (DD-8/DD-11).';
comment on column coreapp.audit_events.details is
    'Free structured payload (before/after, reason). jsonb; mapped by Hibernate as SqlTypes.JSON.';


-- -----------------------------------------------------------------------------
-- 6. claim_status_transition — regole della macchina a stati (A3)
-- The 14 seeded pairs are the DB-side source of truth for allowed transitions;
-- claim_status_history has a composite FK on this table, so PostgreSQL rejects
-- any illegal pair even if the application check is bypassed.
-- ClaimStatus.allowedTransitions() mirrors this seed exactly (parity test).
-- -----------------------------------------------------------------------------
create table if not exists coreapp.claim_status_transition
(
    from_status varchar(32) not null,
    to_status   varchar(32) not null,
    constraint pk_claim_status_transition primary key (from_status, to_status),
    constraint ck_claim_status_transition_from check (from_status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPRAISAL',
        'APPROVED',
        'REJECTED',
        'SETTLEMENT',
        'CLOSED',
        'ARCHIVED'
    )),
    constraint ck_claim_status_transition_to check (to_status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPRAISAL',
        'APPROVED',
        'REJECTED',
        'SETTLEMENT',
        'CLOSED',
        'ARCHIVED'
    )),
    constraint ck_claim_status_transition_actual_change check (from_status <> to_status)
);

comment on table coreapp.claim_status_transition is
    'Allowed claim status transitions (state machine enforced by the database).';

insert into coreapp.claim_status_transition (from_status, to_status)
values ('DRAFT', 'SUBMITTED'),
       ('SUBMITTED', 'UNDER_REVIEW'),
       ('UNDER_REVIEW', 'INTEGRATION_REQUESTED'),
       ('UNDER_REVIEW', 'APPRAISAL'),
       ('UNDER_REVIEW', 'APPROVED'),
       ('UNDER_REVIEW', 'REJECTED'),
       ('INTEGRATION_REQUESTED', 'UNDER_REVIEW'),
       ('APPRAISAL', 'UNDER_REVIEW'),
       ('APPRAISAL', 'APPROVED'),
       ('APPRAISAL', 'REJECTED'),
       ('APPROVED', 'SETTLEMENT'),
       ('SETTLEMENT', 'CLOSED'),
       ('REJECTED', 'CLOSED'),
       ('CLOSED', 'ARCHIVED')
on conflict (from_status, to_status) do nothing;


-- -----------------------------------------------------------------------------
-- 7. claims — date di chiusura/archiviazione e CHECK a 10 valori
-- -----------------------------------------------------------------------------
alter table coreapp.claims add column if not exists closed_at timestamptz;
alter table coreapp.claims add column if not exists archived_at timestamptz;

-- Widening a CHECK is not re-runnable in place: drop + re-add (idempotent).
alter table coreapp.claims drop constraint if exists ck_claims_status;
alter table coreapp.claims
    add constraint ck_claims_status check (status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPRAISAL',
        'APPROVED',
        'REJECTED',
        'SETTLEMENT',
        'CLOSED',
        'ARCHIVED'
    ));

alter table coreapp.claims drop constraint if exists ck_claims_closed_at_requires_closed_status;
alter table coreapp.claims
    add constraint ck_claims_closed_at_requires_closed_status
        check (closed_at is null or status in ('CLOSED', 'ARCHIVED'));

alter table coreapp.claims drop constraint if exists ck_claims_archived_at_requires_archived_status;
alter table coreapp.claims
    add constraint ck_claims_archived_at_requires_archived_status
        check (archived_at is null or status = 'ARCHIVED');

alter table coreapp.claims drop constraint if exists ck_claims_closed_at_after_opened_at;
alter table coreapp.claims
    add constraint ck_claims_closed_at_after_opened_at
        check (closed_at is null or closed_at >= opened_at);

-- Reporting: pratiche chiuse nel periodo.
create index if not exists idx_claims_closed_at
    on coreapp.claims (closed_at desc) where closed_at is not null;

comment on column coreapp.claims.closed_at is
    'Timestamp of the transition into CLOSED (chiusa). NULL while the claim is open.';
comment on column coreapp.claims.archived_at is
    'Timestamp of the transition into ARCHIVED (archiviata). ARCHIVED is terminal.';


-- -----------------------------------------------------------------------------
-- 8. claim_status_history — autore della transizione + coppia imposta dal DB
-- -----------------------------------------------------------------------------
alter table coreapp.claim_status_history add column if not exists actor_id uuid;

alter table coreapp.claim_status_history drop constraint if exists fk_claim_status_history_actor;
alter table coreapp.claim_status_history
    add constraint fk_claim_status_history_actor foreign key (actor_id)
        references coreapp.actors (id) on delete set null;

-- 10-value CHECKs (V1 declared 7): drop + re-add of the named constraints.
alter table coreapp.claim_status_history
    drop constraint if exists ck_claim_status_history_from_status;
alter table coreapp.claim_status_history
    add constraint ck_claim_status_history_from_status check (from_status is null or from_status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPRAISAL',
        'APPROVED',
        'REJECTED',
        'SETTLEMENT',
        'CLOSED',
        'ARCHIVED'
    ));

alter table coreapp.claim_status_history
    drop constraint if exists ck_claim_status_history_to_status;
alter table coreapp.claim_status_history
    add constraint ck_claim_status_history_to_status check (to_status in (
        'DRAFT',
        'SUBMITTED',
        'UNDER_REVIEW',
        'INTEGRATION_REQUESTED',
        'APPRAISAL',
        'APPROVED',
        'REJECTED',
        'SETTLEMENT',
        'CLOSED',
        'ARCHIVED'
    ));

-- Guard BEFORE the FK (docs/db/README.md §7.4: fail loud, never relax silently).
-- On a fresh database this block is a no-op. On a database that already stored a
-- transition the new machine does not allow (e.g. the removed APPROVED -> CLOSED,
-- decision A10) the migration aborts with the offending pairs and an actionable
-- hint, and the operator decides (backfill the rules table or the history).
do $$
declare
    offending text;
begin
    select string_agg(distinct offending_pair, ', ' order by offending_pair)
      into offending
      from (select h.from_status || ' -> ' || h.to_status as offending_pair
              from coreapp.claim_status_history h
             where h.from_status is not null
               and not exists (select 1
                                 from coreapp.claim_status_transition t
                                where t.from_status = h.from_status
                                  and t.to_status = h.to_status)) as illegal;

    if offending is not null then
        raise exception
            'V2__claim_workflow_actors_audit aborted: claim_status_history contains transition(s) not allowed by the new state machine: %',
            offending
            using hint = 'Either insert the missing pairs into coreapp.claim_status_transition '
                         '(if they are legitimately allowed) or normalise the history, then re-run the '
                         'migration. See docs/db/README.md §7 (APPROVED -> CLOSED was replaced by '
                         'APPROVED -> SETTLEMENT -> CLOSED, decision A10).';
    end if;
end;
$$;

-- The composite FK is MATCH SIMPLE on purpose: a NULL from_status (the opening
-- row of a claim, which has no previous state) makes PostgreSQL skip the check.
-- That is the intended behaviour — see docs/db/README.md §4.8.
alter table coreapp.claim_status_history
    drop constraint if exists fk_claim_status_history_transition;
alter table coreapp.claim_status_history
    add constraint fk_claim_status_history_transition foreign key (from_status, to_status)
        references coreapp.claim_status_transition (from_status, to_status);

comment on column coreapp.claim_status_history.actor_id is
    'Actor who performed the transition (nullable: rows written before TK-64FBC8).';


-- -----------------------------------------------------------------------------
-- 9. claim_documents — soft un-link + autore del collegamento
-- -----------------------------------------------------------------------------
alter table coreapp.claim_documents add column if not exists linked_by_actor_id uuid;
alter table coreapp.claim_documents add column if not exists unlinked_at timestamptz;

alter table coreapp.claim_documents
    drop constraint if exists fk_claim_documents_linked_by_actor;
alter table coreapp.claim_documents
    add constraint fk_claim_documents_linked_by_actor foreign key (linked_by_actor_id)
        references coreapp.actors (id) on delete set null;

alter table coreapp.claim_documents
    drop constraint if exists ck_claim_documents_unlinked_after_linked;
alter table coreapp.claim_documents
    add constraint ck_claim_documents_unlinked_after_linked
        check (unlinked_at is null or unlinked_at >= linked_at);

-- Documenti attualmente collegati alla pratica (filtro di default dell'API).
create index if not exists idx_claim_documents_active
    on coreapp.claim_documents (claim_id, linked_at desc) where unlinked_at is null;

comment on column coreapp.claim_documents.unlinked_at is
    'Soft un-link marker: non-null means the document is no longer attached to the claim.';
