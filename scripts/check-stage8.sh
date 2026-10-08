#!/usr/bin/env bash
set -euo pipefail
# This gate uses one already-running local instance. It never starts or deploys services.
mkdir -p app/build/stage8
credentials="$PWD/app/build/stage8/credentials.json"
cleanup() {
  if [[ -f "$credentials" ]]; then
    python3 scripts/prepare-stage8-local.py cleanup "$credentials"
  fi
}
trap cleanup EXIT
python3 scripts/prepare-stage8-local.py prepare "$credentials"
export STILLROOM_STAGE8_FIXTURES="$credentials"
python3 -c 'from pathlib import Path; Path("app/build/reports/stage8/local-parity.json").unlink(missing_ok=True)'
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :fractions:test :app:householdCoverageVerification --rerun-tasks
python3 - <<'PY'
import json
from pathlib import Path
report=json.loads(Path('app/build/reports/stage8/local-parity.json').read_text())
assert report['child_user_id']==report['log_done_by']
assert report['parent_crud_reassignment_tasks']
print('Stage 8: child identity matches the local Grocy chore log; server due dates, parent CRUD/reassignment, and tasks verified.')
PY
