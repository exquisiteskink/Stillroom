#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-grocy-fixtures.sh
mkdir -p app/build/stage6
credentials="$PWD/app/build/stage6/credentials.json"
cleanup() {
  if [[ -f "$credentials" ]]; then
    python scripts/prepare-stage3-fixtures.py cleanup "$credentials"
    python -c 'import sys; from pathlib import Path; Path(sys.argv[1]).unlink()' "$credentials"
  fi
}
trap cleanup EXIT
python scripts/prepare-stage3-fixtures.py prepare "$credentials"
export STILLROOM_STAGE6_FIXTURES="$credentials"
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :fractions:test :app:stockCoverageReport :app:stockCoverageVerification --rerun-tasks
test -s app/build/reports/stage6/container.json
