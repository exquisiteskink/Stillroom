#!/usr/bin/env bash
set -euo pipefail
./gradlew --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:syncCoverageReport :app:syncCoverageVerification
test -s app/build/reports/jacoco/syncCoverageReport/syncCoverageReport.xml
