#!/usr/bin/env bash
set -euo pipefail
# Only the existing local Grocy; no container creation or deployment.
mkdir -p app/build/stage10
credentials="$PWD/app/build/stage10/credentials.json"
cleanup() {
  if [[ -f "$credentials" ]]; then
    python3 scripts/prepare-stage10-local.py cleanup "$credentials"
  fi
}
trap cleanup EXIT
python3 scripts/prepare-stage10-local.py prepare "$credentials"
export STILLROOM_STAGE10_FIXTURES="$credentials"
python3 -c 'from pathlib import Path; Path("app/build/reports/stage10/local-parity.json").unlink(missing_ok=True)'
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :fractions:test :app:recipesCoverageVerification
python3 - <<'PY'
import json
from pathlib import Path
r=json.loads(Path('app/build/reports/stage10/local-parity.json').read_text())
assert r['scaled_required_stock_units']==5 and r['missing_added_chosen_list']==2
assert r['consumed_stock_units']==5 and r['consume_journal_rows']==1
assert r['base_amount_unchanged'] and r['meal_crud_images_source_undo']
print('Stage 10: existing local Grocy recipe scaling, chosen shopping list, reviewed stock consumption/journal, meal plan, images, source, and undo match native records.')
PY
