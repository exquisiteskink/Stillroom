#!/usr/bin/env bash
set -euo pipefail
test -s docs/FRACTIONS.md
./gradlew --no-daemon :fractions:test :fractions:jacocoTestReport :fractions:jacocoTestCoverageVerification :app:assembleDebug :app:testDebugUnitTest
