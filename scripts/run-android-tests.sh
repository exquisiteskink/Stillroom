#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Run through mise}"
stage="${1:-2}"
[[ "$stage" == 2 || "$stage" == 3 ]] || { echo 'Expected stage 2 or 3.' >&2; exit 1; }

./gradlew --no-daemon assembleDebug testDebugUnitTest assembleDebugAndroidTest

# Own an isolated emulator; never resize or change settings on a user's device.
adb="$ANDROID_HOME/platform-tools/adb"
# AVD images need several GB; /tmp may be a small RAM-backed filesystem.
scratch=$(mktemp -d "$PWD/app/build/stage${stage}-avd.XXXXXX")
emulator_pid=""
fixture_file="$scratch/fixtures.json"
fixture_prepared=false
reports="app/build/reports/stage$stage"
mkdir -p "$reports"
cleanup() {
  local result=$?
  trap - EXIT
  set +e
  if [[ "$stage" == 3 && -f "$fixture_file" ]]; then
    python scripts/prepare-stage3-fixtures.py cleanup "$fixture_file" >"$reports/cleanup.txt" 2>&1
    [[ $? == 0 ]] || result=1
  fi
  if [[ -n "$emulator_pid" ]]; then
    "$adb" -s "$serial" shell run-as app.stillroom rm -f files/stage3-fixtures.json >/dev/null 2>&1
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -rf "$scratch"
  exit "$result"
}
trap cleanup EXIT
if [[ "$stage" == 3 ]]; then
  python scripts/prepare-stage3-fixtures.py prepare "$fixture_file"
  fixture_prepared=true
fi
export ANDROID_AVD_HOME="$scratch/avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager" create avd \
  --name "stillroom-stage$stage" --package 'system-images;android-35;default;x86_64'
cat >>"$ANDROID_AVD_HOME/stillroom-stage$stage.avd/config.ini" <<'EOF'
hw.lcd.width=412
hw.lcd.height=915
hw.lcd.density=160
EOF

port=""
for candidate in $(seq 5580 2 5680); do
  if ! ss -ltnH | awk '{print $4}' | grep -Eq ":($candidate|$((candidate + 1)))$"; then
    port="$candidate"
    break
  fi
done
[[ -n "$port" ]] || { echo 'No free emulator port.' >&2; exit 1; }
serial="emulator-$port"
"$ANDROID_HOME/emulator/emulator" -avd "stillroom-stage$stage" -port "$port" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -memory 1536 -cores 2 >"$reports/emulator.log" 2>&1 &
emulator_pid=$!

booted=false
for attempt in $(seq 1 180); do
  kill -0 "$emulator_pid" 2>/dev/null || { cat "$reports/emulator.log"; exit 1; }
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Emulator boot timed out.' >&2; exit 1; }
"$adb" -s "$serial" shell input keyevent 82
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [[ "$stage" == 3 ]]; then
  # Transfer through stdin into private app storage; keys never appear in command arguments.
  "$adb" -s "$serial" reverse tcp:9283 tcp:9283
  "$adb" -s "$serial" reverse tcp:9284 tcp:9284
  "$adb" -s "$serial" shell run-as app.stillroom mkdir -p files
  "$adb" -s "$serial" shell 'run-as app.stillroom sh -c "umask 077; cat > files/stage3-fixtures.json"' <"$fixture_file"
fi

run_ui() {
  local name="$1" size="$2" scale="$3" width_class="$4" classes="$5"
  "$adb" -s "$serial" shell wm size "$size"
  "$adb" -s "$serial" shell wm density 160
  "$adb" -s "$serial" shell settings put system font_scale "$scale"
  "$adb" -s "$serial" shell am force-stop app.stillroom
  "$adb" -s "$serial" shell am force-stop app.stillroom.test
  timeout 300 "$adb" -s "$serial" shell am instrument -w -r \
    -e class "$classes" \
    -e expectedWidthClass "$width_class" -e expectedFontScale "$scale" \
    app.stillroom.test/androidx.test.runner.AndroidJUnitRunner \
    | python -u scripts/redact-test-output.py "$fixture_file" | tee "$reports/$name.txt"
  "$adb" -s "$serial" logcat -d -b crash \
    | python scripts/redact-test-output.py "$fixture_file" >"$reports/$name-crash.txt"
  grep -Eq 'OK \([1-9][0-9]* tests\)' "$reports/$name.txt"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$reports/$name.txt"
}

shell_classes="app.stillroom.ShellUiTest,app.stillroom.MainActivityUiTest"
phone_classes="$shell_classes"
if [[ "$stage" == 3 ]]; then
  phone_classes+=",app.stillroom.AccountIntegrationTest,app.stillroom.AccountUiTest,app.stillroom.NetworkPolicyTest"
fi
run_ui phone 412x915 1.0 phone "$phone_classes"
run_ui tablet 840x1200 1.0 tablet "$shell_classes"
run_ui phone-large-text 412x915 2.0 phone "$shell_classes"
if [[ "$fixture_prepared" == true ]]; then
  python scripts/prepare-stage3-fixtures.py cleanup "$fixture_file" | tee "$reports/cleanup.txt"
  rm -f "$fixture_file"
fi
echo "Stage $stage passed. Reports: $reports"
