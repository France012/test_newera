#!/bin/sh
# =============================================================================
# TK-DE445B — build the four container images locally (AC-8).
# Requires a working Docker daemon; the CI workflow container-images.yml is the
# reference implementation (it also pushes, signs and attests).
# Usage: infra/scripts/build-images.sh [registry/namespace]
# =============================================================================
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
cd "${REPO_ROOT}"

VERSION=$(tr -d '[:space:]' < VERSION)
SHORT_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "nogit")

OWNER_DEFAULT="ghcr.io/france012/test_newera"
IMAGE_BASE=${1:-${IMAGE_BASE:-${OWNER_DEFAULT}}}

# service:dockerfile
IMAGES="core-app:infra/docker/core-app.Dockerfile \
document-service:infra/docker/document-service.Dockerfile \
bff:infra/docker/bff.Dockerfile \
frontend:infra/docker/frontend.Dockerfile"

echo "Building claims-platform ${VERSION} (${SHORT_SHA}) into ${IMAGE_BASE}"

for entry in ${IMAGES}; do
  service=${entry%%:*}
  dockerfile=${entry#*:}
  echo "==> ${service} (${dockerfile})"
  docker build \
    --file "${dockerfile}" \
    --tag "${IMAGE_BASE}/${service}:${VERSION}" \
    --tag "${IMAGE_BASE}/${service}:sha-${SHORT_SHA}" \
    --label "org.opencontainers.image.title=${service}" \
    --label "org.opencontainers.image.version=${VERSION}" \
    --label "org.opencontainers.image.revision=${SHORT_SHA}" \
    --label "org.opencontainers.image.source=https://github.com/France012/test_newera" \
    .
done

echo "Done. Images tagged ${VERSION} and sha-${SHORT_SHA} under ${IMAGE_BASE}."
echo "Next: infra/scripts/generate-sbom.sh, then infra/scripts/sign-images.sh (needs cosign)."
