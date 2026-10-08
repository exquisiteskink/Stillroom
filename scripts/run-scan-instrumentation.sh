#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Run through mise}"
adb="$ANDROID_HOME/platform-tools/adb"
reports="$PWD/app/build/reports/stage9"
mkdir -p "$reports"
scratch=$(mktemp -d "$PWD/app/build/stage9-avd.XXXXXX")
emulator_pid=""
cleanup() {
  local result=$?
  trap - EXIT
  if [[ -n "$emulator_pid" ]]; then
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  python3 - "$scratch" <<'PY'
import shutil,sys
shutil.rmtree(sys.argv[1])
PY
  exit "$result"
}
trap cleanup EXIT
export ANDROID_AVD_HOME="$scratch/avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager" create avd --name stillroom-stage9 --package 'system-images;android-35;default;x86_64'
port=""
for candidate in $(seq 5580 2 5680); do
  if ! ss -ltnH | awk '{print $4}' | grep -Eq ":($candidate|$((candidate+1)))$"; then port="$candidate";break;fi
done
[[ -n "$port" ]]
serial="emulator-$port"
"$ANDROID_HOME/emulator/emulator" -avd stillroom-stage9 -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 1536 -cores 2 >"$reports/emulator.log" 2>&1 &
emulator_pid=$!
booted=false
for attempt in $(seq 1 180); do
  kill -0 "$emulator_pid" 2>/dev/null || { cat "$reports/emulator.log";exit 1; }
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true;break;fi
  sleep 2
done
[[ "$booted" == true ]]
"$adb" -s "$serial" shell input keyevent 82
# Bundled detector gate deliberately runs without emulator network connectivity or Grocy credentials.
"$adb" -s "$serial" shell svc wifi disable
"$adb" -s "$serial" shell svc data disable
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell am instrument -w -r -e class app.stillroom.ScannerFixtureTest app.stillroom.test/androidx.test.runner.AndroidJUnitRunner >"$reports/instrumentation.txt"
cat "$reports/instrumentation.txt"
grep -Eq 'OK \([0-9]+ tests?\)' "$reports/instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$reports/instrumentation.txt"
printf '{"fixture_image":true,"bundled_mlkit":true,"physical_phone":false}\n' >"$reports/fixture-scan.json"
