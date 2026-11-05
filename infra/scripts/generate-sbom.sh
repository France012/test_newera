#!/bin/sh
# =============================================================================
# TK-DE445B — SBOM generation (AC-10).
#  1. Maven aggregate CycloneDX BOM of the whole platform (always).
#  2. One CycloneDX BOM per image (only when syft is installed).
# Degrades gracefully with a clear message when the optional tools are missing.
# =============================================================================
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
cd "${REPO_ROOT}"

OUT_DIR="${REPO_ROOT}/sbom"
mkdir -p "${OUT_DIR}"

VERSION=$(tr -d '[:space:]' < VERSION)
IMAGE_BASE=${1:-${IMAGE_BASE:-ghcr.io/france012/test_newera}}

echo "==> Maven aggregate SBOM (CycloneDX)"
mvn -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom
if [ -f "${REPO_ROOT}/target/sbom-claims-platform.json" ]; then
  cp "${REPO_ROOT}/target/sbom-claims-platform.json" "${OUT_DIR}/sbom-claims-platform.json"
  echo "    written ${OUT_DIR}/sbom-claims-platform.json"
else
  echo "    WARNING: aggregate SBOM not found under target/" >&2
fi

echo "==> Per-image SBOMs"
if ! command -v syft >/dev/null 2>&1; then
  echo "    syft not installed: skipping per-image SBOMs." >&2
  echo "    Install it (https://github.com/anchore/syft) or rely on the" >&2
  echo "    container-images.yml workflow, which produces them in CI." >&2
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "    docker not installed: cannot inspect local images, skipping." >&2
  exit 0
fi

for service in core-app document-service bff frontend; do
  image="${IMAGE_BASE}/${service}:${VERSION}"
  echo "    ${image}"
  syft "docker:${image}" -o cyclonedx-json >"${OUT_DIR}/sbom-${service}.cdx.json"
done

echo "SBOMs written to ${OUT_DIR}"
