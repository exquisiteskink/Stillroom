#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for artifact in docs/FEATURE_MATRIX.md docs/COMPATIBILITY.md docs/STATUS.md docs/evidence/stage1/manifest.json; do
  test -s "$artifact"
done
sha256sum --check docs/evidence/stage1/SHA256SUMS
printf 'Stage 1 documentation and archived live-response integrity gate passed. This does not rerun HTTP probes.\n'
