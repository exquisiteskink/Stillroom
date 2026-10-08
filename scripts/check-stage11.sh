#!/usr/bin/env bash
set -euo pipefail
# Last build stage: use the existing local Grocy; no container startup or deployment.
mkdir -p app/build/stage11
credentials="$PWD/app/build/stage11/credentials.json"
cleanup() {
  if [[ -f "$credentials" ]]; then
    python3 scripts/prepare-stage11-local.py cleanup "$credentials"
  fi
}
trap cleanup EXIT
python3 scripts/prepare-stage11-local.py prepare "$credentials"
export STILLROOM_STAGE11_FIXTURES="$credentials"
python3 -c 'from pathlib import Path; Path("app/build/reports/stage11/local-parity.json").unlink(missing_ok=True)'
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :fractions:test :app:catalogCoverageVerification
python3 - <<'PY'
import json
from pathlib import Path
r=json.loads(Path('app/build/reports/stage11/local-parity.json').read_text())
assert r['native_cycle_count']==1 and r['cycle_matches_history'] and r['server_next_charge_date']
assert r['conversion_factor']==2.5 and r['edited_factor']==3 and r['equipment_custom_field'] and r['undo']
assert 'NOT RUN' in Path('docs/PHONE_TEST.md').read_text()
print('Stage 11: native battery cycle/history/next date and unit conversion match existing local Grocy; build, regressions, coverage, and phone-test plan passed. Physical-phone results remain NOT RUN.')
PY
