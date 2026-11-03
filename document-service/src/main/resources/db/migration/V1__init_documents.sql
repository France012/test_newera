-- =============================================================================
-- TK-DE445B — document-service persistence schema (PostgreSQL 16, schema: documents)
-- -----------------------------------------------------------------------------
-- Module      : document-service (:8181)   Strategy: database-per-service
-- Tool        : Flyway 10 (changelog by version, forward-only, IMMUTABLE)
-- Owner       : it.enterprise.claims.documents
-- Applied via : spring.flyway.locations=classpath:db/migration
--               spring.flyway.schemas=documents
--               spring.flyway.default-schema=documents
--
-- RULES
--  * NEVER edit this file after it has been applied to any environment.
--    Any schema change requires a new V<n>__<snake_case_description>.sql.
--  * All objects are fully qualified with the "documents" schema.
--  * UUID primary keys are generated in the application layer (blueprint 2.6).
--  * claim_id is a LOGICAL reference to core-app: no cross-service foreign key.
--  * Only document METADATA lives here; binary content is stored through
--    DocumentStoragePort (LocalFileSystemStorage today, S3 later) and located
--    by storage_key.
-- =============================================================================

create schema if not exists documents;

-- -----------------------------------------------------------------------------
-- documents — metadata of an uploaded claim document
-- -----------------------------------------------------------------------------
create table documents.documents
(
    id              uuid         not null,
    claim_id        uuid         not null,
    file_name       varchar(255) not null,
    content_type    varchar(128) not null,
    size_bytes      bigint       not null,
    document_type   varchar(32)  not null,
    checksum_sha256 varchar(64)  not null,
    storage_key     varchar(512) not null,
    version         integer      not null default 1,
    uploaded_at     timestamptz  not null default now(),
    deleted_at      timestamptz,
    constraint pk_documents primary key (id),
    constraint uk_documents_storage_key unique (storage_key),
    constraint ck_documents_size_bytes_non_negative check (size_bytes >= 0),
    constraint ck_documents_checksum_sha256_hex check (checksum_sha256 ~* '^[0-9a-f]{64}$'),
    constraint ck_documents_file_name_not_blank check (length(btrim(file_name)) > 0),
    constraint ck_documents_storage_key_not_blank check (length(btrim(storage_key)) > 0),
    constraint ck_documents_deleted_at_after_uploaded_at check (deleted_at is null or deleted_at >= uploaded_at)
);

-- GET /api/v1/documents?claimId=... (required by the bff claim detail aggregation)
create index idx_documents_claim on documents.documents (claim_id);
-- Partial index for the hot path: only live documents are ever served
create index idx_documents_claim_active on documents.documents (claim_id) where deleted_at is null;
-- Dashboard / latest uploads ordering
create index idx_documents_uploaded_at on documents.documents (uploaded_at desc);

comment on table documents.documents is
    'Document metadata. Physical content is addressed by storage_key through DocumentStoragePort.';
comment on column documents.documents.claim_id is
    'Logical reference to core-app claim id (no cross-service FK by design).';
comment on column documents.documents.deleted_at is
    'Soft delete marker: non-null means the document is logically deleted and must not be served.';
comment on column documents.documents.version is
    'Optimistic locking counter managed by Hibernate (@Version).';
comment on column documents.documents.checksum_sha256 is
    'Hex SHA-256 (64 chars) of the stored content. varchar(64) instead of char(64) on purpose:'
    ' bpchar is not type-compatible with the Hibernate String mapping under ddl-auto=validate'
    ' (decision DD-2 in docs/db/README.md); length and hex format are enforced by CHECKs.';
