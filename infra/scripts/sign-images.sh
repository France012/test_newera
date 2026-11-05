#!/bin/sh
# =============================================================================
# TK-DE445B — sign the four container images (AC-9, ADR-0003).
# Keyless (OIDC) signing: no private key is stored in the repository.
# Requires: cosign, an authenticated registry session, and a pushed image.
# Usage: infra/scripts/sign-images.sh [registry/namespace]
# =============================================================================
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
cd "${REPO_ROOT}"

VERSION=$(tr -d '[:space:]' < VERSION)
IMAGE_BASE=${1:-${IMAGE_BASE:-ghcr.io/france012/test_newera}}

if ! command -v cosign >/dev/null 2>&1; then
  echo "ERROR: cosign is not installed." >&2
  echo "       Install it from https://docs.sigstore.dev/cosign/installation/ and retry." >&2
  echo "       In CI the signature is produced by .github/workflows/container-images.yml." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is not installed: cannot resolve the image digest." >&2
  exit 1
fi

echo "Signing claims-platform ${VERSION} images in ${IMAGE_BASE} (keyless)"

for service in core-app document-service bff frontend; do
  image="${IMAGE_BASE}/${service}:${VERSION}"
  echo "==> ${image}"
  docker pull --quiet "${image}"
  # Sign by digest, never by tag: a tag can be moved, a digest cannot.
  digest=$(docker inspect --format '{{index .RepoDigests 0}}' "${image}")
  echo "    ${digest}"
  cosign sign --yes "${digest}"
done

echo "Done. Verify with:"
echo "  cosign verify \\"
echo "    --certificate-identity-regexp 'https://github.com/France012/test_newera/.*' \\"
echo "    --certificate-oidc-issuer https://token.actions.githubusercontent.com \\"
echo "    ${IMAGE_BASE}/core-app@<digest>"
