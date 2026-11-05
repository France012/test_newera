-- =============================================================================
-- TK-64FBC8 — document-service: versioning documentale, key_ref e data retention
-- (schema: documents)
-- -----------------------------------------------------------------------------
-- Module      : document-service (:8181)   Strategy: database-per-service
-- Tool        : Flyway 10 (changelog by version, forward-only)
-- Owner       : it.enterprise.claims.documents
-- Applied via : spring.flyway.locations=classpath:db/migration
--               spring.flyway.schemas=documents
--               spring.flyway.default-schema=documents
--
-- RULES (docs/db/README.md §7)
--  * V1__init_documents.sql is IMMUTABLE: every change of TK-64FBC8 lands here.
--  * IDEMPOTENT and applicable FROM ZERO together with V1: guarded DDL
--    (`add column if not exists`, `drop constraint if exists` + `add constraint`,
--    `create index if not exists`). Re-running on a migrated database is a no-op.
--  * Rolling-deploy safe (§7.4): new columns are added nullable or with a
--    DEFAULT, the backfill runs BEFORE `set not null`.
--  * All objects are fully qualified with the "documents" schema (DD-9).
--  * `claim_id` stays a LOGICAL reference to core-app: no cross-service FK
--    is added by this migration (DD-5).
--  * No triggers (DD-8).
--
-- SEMANTICS (decision A6/A7)
--  * `document_key`  = logical identity of a document, shared by all its versions.
--  * `version_number`= business version, 1..N, linear chain (no forks).
--  * `is_current`    = exactly one live current version per document_key
--                      (enforced by the partial unique index below).
--  * `version`       = UNCHANGED: it remains the Hibernate @Version optimistic
--                      locking counter and must never carry business meaning.
--  * `storage_key`   = physical object locator (unique per object).
--  * `key_ref`       = opaque reference to the encryption key (KMS alias/version).
--                      Not a secret: it is exposed by the API, storage_key is not.
--  * `retention_until` + `legal_hold` = retention policy consumed by a purge job
--                      that stays OUT OF SCOPE of this task.
-- =============================================================================

create schema if not exists documents;

-- -----------------------------------------------------------------------------
-- 1. New columns — nullable or defaulted so a rolling deploy never breaks
-- -----------------------------------------------------------------------------

-- Logical document identity; backfilled with the existing id below.
alter table documents.documents add column if not exists document_key uuid;

alter table documents.documents add column if not exists version_number integer not null default 1;
alter table documents.documents add column if not exists is_current boolean not null default true;

-- Opaque KMS reference (alias/version of the key used to encrypt the object).
alter table documents.documents add column if not exists key_ref varchar(160);

-- Retention policy: end date of the mandatory storage period.
alter table documents.documents add column if not exists retention_until date;

-- Legal hold: blocks both the purge job and the soft delete, regardless of
-- retention_until.
alter table documents.documents add column if not exists legal_hold boolean not null default false;

-- Previous version of the same document_key (linear chain: 1 -> 1 predecessor).
alter table documents.documents add column if not exists supersedes_id uuid;

-- -----------------------------------------------------------------------------
-- 2. Backfill — every existing row is the first (and current) version of itself
-- -----------------------------------------------------------------------------
update documents.documents
   set document_key = id
 where document_key is null;

alter table documents.documents alter column document_key set not null;

comment on column documents.documents.document_key is
    'Logical identity of a document, shared by all its versions (A6).';
comment on column documents.documents.version_number is
    'Business version number (1..N) inside the document_key chain.';
comment on column documents.documents.is_current is
    'Exactly one live current version per document_key (partial unique index).';
comment on column documents.documents.key_ref is
    'Opaque encryption-key reference (KMS alias/version). Never a secret, never storage_key.';
comment on column documents.documents.retention_until is
    'End of the mandatory retention period (purge job out of scope).';
comment on column documents.documents.legal_hold is
    'When true the document cannot be purged nor soft-deleted, regardless of retention_until.';
comment on column documents.documents.supersedes_id is
    'Previous version of the same document_key; unique, so the version chain cannot fork.';

-- -----------------------------------------------------------------------------
-- 3. Constraints — dropped and re-added so the migration is re-runnable
-- -----------------------------------------------------------------------------
alter table documents.documents drop constraint if exists ck_documents_version_number_positive;
alter table documents.documents
    add constraint ck_documents_version_number_positive check (version_number >= 1);

alter table documents.documents
    drop constraint if exists uk_documents_document_key_version;
alter table documents.documents
    add constraint uk_documents_document_key_version unique (document_key, version_number);

alter table documents.documents drop constraint if exists ck_documents_key_ref_format;
alter table documents.documents
    add constraint ck_documents_key_ref_format
        check (key_ref is null or key_ref ~ '^[A-Za-z0-9._:/-]{1,160}$');

-- A retention date in the past is rejected at the API too (400); this is the
-- second line of defence.
alter table documents.documents drop constraint if exists ck_documents_retention_after_upload;
alter table documents.documents
    add constraint ck_documents_retention_after_upload
        check (retention_until is null or retention_until >= timezone('UTC', uploaded_at)::date);

alter table documents.documents drop constraint if exists fk_documents_supersedes;
alter table documents.documents
    add constraint fk_documents_supersedes foreign key (supersedes_id)
        references documents.documents (id) on delete set null;

alter table documents.documents drop constraint if exists uk_documents_supersedes;
alter table documents.documents
    add constraint uk_documents_supersedes unique (supersedes_id);

alter table documents.documents drop constraint if exists ck_documents_supersedes_not_self;
alter table documents.documents
    add constraint ck_documents_supersedes_not_self
        check (supersedes_id is null or supersedes_id <> id);

-- -----------------------------------------------------------------------------
-- 4. Indexes serving the new query paths
-- -----------------------------------------------------------------------------

-- One live current version per document_key: the "server serves the current
-- version" invariant is enforced here, not by a MAX() scan in the application.
create unique index if not exists uk_documents_current_version
    on documents.documents (document_key) where is_current and deleted_at is null;

-- GET /api/v1/documents/{documentId}/versions (newest first).
create index if not exists idx_documents_key_versions
    on documents.documents (document_key, version_number desc);

-- Purge-job selector: retention expired, not on legal hold, not already deleted.
-- (The job itself is out of scope: this index only makes it cheap.)
create index if not exists idx_documents_retention_due
    on documents.documents (retention_until)
    where retention_until is not null and legal_hold = false and deleted_at is null;
