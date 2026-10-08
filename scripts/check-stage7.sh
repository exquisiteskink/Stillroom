#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-grocy-fixtures.sh
mkdir -p app/build/stage7
credentials="$PWD/app/build/stage7/credentials.json"
cleanup() {
  if [[ -f "$credentials" ]]; then
    python scripts/prepare-stage3-fixtures.py cleanup "$credentials"
    python -c 'import sys; from pathlib import Path; Path(sys.argv[1]).unlink()' "$credentials"
  fi
}
trap cleanup EXIT
python scripts/prepare-stage3-fixtures.py prepare "$credentials"
export STILLROOM_STAGE7_FIXTURES="$credentials"
export STILLROOM_STAGE6_FIXTURES="$credentials"
python -c 'from pathlib import Path; Path("app/build/reports/stage7/container.json").unlink(missing_ok=True)'
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :fractions:test :app:shoppingCoverageReport :app:shoppingCoverageVerification --rerun-tasks
test -s app/build/reports/stage7/container.json
