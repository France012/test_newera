# Infrastructure

Everything needed to run the claims platform outside a bare `mvnw` / `npm` shell: container images,
local orchestration, Kubernetes base manifests and helper scripts.

> **Sandbox note**: `docker`, `docker-compose`, `cosign`, `syft` and `trivy` are **not installed** in
> the development sandbox. The Dockerfiles and workflows below are validated **statically** here
> (multi-stage count, `USER`, `HEALTHCHECK`, `EXPOSE`, YAML parsing) and executed for real only in
> CI (`.github/workflows/container-images.yml`).

## Layout

```
infra/
├── db/init/01-init-schemas.sql      # dev-only provisioning of schemas coreapp + documents
├── docker/
│   ├── core-app.Dockerfile          # Java 21 service, port 8180
│   ├── document-service.Dockerfile  # Java 21 service, port 8181 (+ volume)
│   ├── bff.Dockerfile               # Java 21 service, port 8185
│   ├── frontend.Dockerfile          # Vite build → unprivileged nginx, port 8188
│   └── nginx/default.conf           # SPA fallback, /bff proxy, security headers
├── docker-compose.yml               # PostgreSQL 16 + 3 services + frontend
├── k8s/base/                        # namespace, 4 Deployments + Services, ConfigMap, PVC
└── scripts/                         # build-images.sh, generate-sbom.sh, sign-images.sh
```

## Local full stack

```bash
cp .env.example .env          # replace every __change_me__ (the file is git-ignored)
docker compose -f infra/docker-compose.yml up --build
```

| URL                                    | What                                         |
| -------------------------------------- | -------------------------------------------- |
| http://localhost:8188                  | frontend (nginx, unprivileged)               |
| http://localhost:8188/bff/v1/…         | same-origin API, proxied by nginx to the BFF |
| http://localhost:8185/bff/v1/…         | BFF directly                                 |
| http://localhost:8180/api/v1/…         | core-app directly (+ `/swagger-ui.html`)     |
| http://localhost:8181/api/v1/documents | document-service directly                    |
| localhost:5442                         | PostgreSQL 16                                |

The BFF is only reachable once both downstream services report healthy
(`depends_on: condition: service_healthy`); the frontend waits for the BFF.

## Images

All four images are **multi-stage**, run as a **non-root** user and declare a `HEALTHCHECK`:

| Image              | Build stage                      | Runtime                                   | User          | Port |
| ------------------ | -------------------------------- | ----------------------------------------- | ------------- | ---- |
| `core-app`         | `maven:3.9.9-eclipse-temurin-21` | `eclipse-temurin:21-jre-alpine`           | `10001:10001` | 8180 |
| `document-service` | `maven:3.9.9-eclipse-temurin-21` | `eclipse-temurin:21-jre-alpine`           | `10001:10001` | 8181 |
| `bff`              | `maven:3.9.9-eclipse-temurin-21` | `eclipse-temurin:21-jre-alpine`           | `10001:10001` | 8185 |
| `frontend`         | `node:20-alpine`                 | `nginxinc/nginx-unprivileged:1.27-alpine` | `101`         | 8188 |

Build locally:

```bash
infra/scripts/build-images.sh                    # tags <version> and sha-<short>
infra/scripts/generate-sbom.sh                   # aggregate CycloneDX BOM (+ per image with syft)
infra/scripts/sign-images.sh                     # requires cosign + a registry login
```

In CI (`.github/workflows/container-images.yml`) each image is pushed to GHCR, gets a CycloneDX SBOM
uploaded as an artefact, is signed with `cosign` in **keyless** mode and receives a build provenance
attestation.

Verification of a published image:

```bash
cosign verify \
  --certificate-identity-regexp 'https://github.com/France012/test_newera/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  ghcr.io/france012/test_newera/core-app@sha256:<digest>
```

## Kubernetes

```bash
kubectl apply -k infra/k8s/base
```

Prerequisites that are deliberately **not** in git:

- `Secret claims-platform-db` with `POSTGRES_USER` / `POSTGRES_PASSWORD`
  (`kubectl -n claims-platform create secret generic claims-platform-db …`).
- A PostgreSQL instance reachable at the URLs in the `claims-platform-config` ConfigMap; Flyway
  creates the schemas on first start (`V1__init_*.sql`).
- Image digests: the manifests carry `sha256:000…` placeholders. Replace them with the digests signed
  in CI (a tag is mutable, a digest is not).

Every workload runs with `runAsNonRoot`, `allowPrivilegeEscalation: false`,
`readOnlyRootFilesystem: true`, all capabilities dropped and `seccompProfile: RuntimeDefault`; the
namespace enforces the `restricted` Pod Security Standard.

## Troubleshooting

| Symptom                                    | Cause / fix                                                         |
| ------------------------------------------ | ------------------------------------------------------------------- |
| `POSTGRES_PASSWORD is required` on compose | `.env` missing or placeholder not replaced                          |
| nginx container exits immediately          | a `listen` on port < 1024 in `default.conf` (must stay 8188)        |
| BFF returns 503 on `/bff/v1/**`            | a downstream service is down: check `/actuator/health` on 8180/8181 |
| `cosign sign` fails with 403               | not logged in to GHCR, or the token lacks `packages:write`          |
