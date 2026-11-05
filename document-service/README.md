# document-service

Stores claim documents: metadata in PostgreSQL (schema `documents`), content behind the
`DocumentStoragePort` abstraction. Listens on **8181**, base path `/api/v1/documents`.

Build alone with `./mvnw -B -pl document-service -am verify`.

## API (contract of record: `docs/api/document-service.openapi.yaml`)

| Method | Path                             | Notes                                                                      |
| ------ | -------------------------------- | -------------------------------------------------------------------------- |
| POST   | `/api/v1/documents`              | `multipart/form-data`: `file`, `claimId`, `documentType`; 201 + `Location` |
| GET    | `/api/v1/documents/{id}`         | metadata                                                                   |
| GET    | `/api/v1/documents/{id}/content` | streams the bytes as `attachment`                                          |
| GET    | `/api/v1/documents?claimId=…`    | live documents of a claim, newest first                                    |
| DELETE | `/api/v1/documents/{id}`         | soft delete, 204                                                           |
| GET    | `/actuator/health`               | liveness/readiness                                                         |

Upload limits: 20 MB per file (`spring.servlet.multipart.max-file-size`), 22 MB per request; a larger
upload is a `413` `ProblemDetail`.

## Behaviour

- **Ids before content**: the document id is generated first, the storage key is `<claimId>/<documentId>`.
  The client file name is sanitised to a basename and stored as metadata only — it never influences a
  path, and every resolved path is verified to be inside the storage root (traversal rejected).
- **Checksum**: hex SHA-256 of the stored bytes is persisted (`checksum_sha256`), so integrity can be
  verified and deduplication added later without a schema change.
- **Soft delete**: `deleted_at` is set, reads filter it out (partial index `idx_documents_claim_active`).
  Content is kept for the retention period; a physical purge job is out of scope in phase 1.
- **Logical claim reference**: `claimId` is not a foreign key (database-per-service), see DD-5 in
  `docs/db/README.md`.

## Storage backend

`LocalFileSystemStorage` writes to `DOCUMENTS_STORAGE_PATH` (default `./target/storage`). Swapping in S3
means implementing `DocumentStoragePort` (`store`/`load`) — no change to the service, the controller or
the schema.

## Running locally

```bash
export POSTGRES_USER=<user> POSTGRES_PASSWORD=<password>
export DOCUMENTS_STORAGE_PATH=./target/storage
./mvnw -B -pl document-service -am spring-boot:run
curl -s localhost:8181/actuator/health
```

`ddl-auto` is `validate` in every profile: the schema belongs to Flyway
(`src/main/resources/db/migration/V1__init_documents.sql`).

## Tests

`DocumentServiceTest` (Mockito: checksum, sanitisation, soft delete) and `DocumentControllerTest`
(`@WebMvcTest` + multipart). No database, no Docker needed:

```bash
./mvnw -B -pl document-service -am verify
```
