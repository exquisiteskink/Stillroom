#!/usr/bin/env bash
set -euo pipefail
bash scripts/ensure-grocy-fixtures.sh
exec bash scripts/run-android-tests.sh 3
