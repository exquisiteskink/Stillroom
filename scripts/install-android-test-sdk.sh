#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Run through mise}"
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "emulator" "system-images;android-35;default;x86_64"
