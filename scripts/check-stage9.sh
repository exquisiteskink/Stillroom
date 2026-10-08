#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/stage9
credentials="$PWD/app/build/stage9/credentials.json"
cleanup() {
  if [[ -f "$credentials" ]]; then python3 scripts/prepare-stage9-local.py cleanup "$credentials";fi
}
trap cleanup EXIT
python3 scripts/prepare-stage9-local.py prepare "$credentials"
export STILLROOM_STAGE9_FIXTURES="$credentials"
python3 - <<'PY'
from pathlib import Path
for name in ['local-parity.json','fixture-scan.json','instrumentation.txt']:
 Path('app/build/reports/stage9',name).unlink(missing_ok=True)
PY
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest :fractions:test :app:scannerCoverageVerification
bash scripts/run-scan-instrumentation.sh
test -s app/build/reports/stage9/local-parity.json
test -s app/build/reports/stage9/fixture-scan.json
echo 'Stage 9 passed: instrumented bundled-ML-Kit fixture scan and local Grocy reviewed creation / stock / undo.'
