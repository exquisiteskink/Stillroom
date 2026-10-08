#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Run this script through mise}"
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo "Stage 0 SDK bootstrap supports Linux x86_64 (Omarchy/CI)." >&2
  exit 1
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  scratch=$(mktemp -d)
  trap 'rm -rf "$scratch"' EXIT
  curl --fail --location --retry 3     https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip     -o "$scratch/tools.zip"
  # Checksum published in Google's SDK repository metadata.
  echo "5fdcc763663eefb86a5b8879697aa6088b041e70  $scratch/tools.zip" | sha1sum --check --status
  unzip -q "$scratch/tools.zip" -d "$scratch"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$scratch/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
fi
# Installing these packages accepts their Android SDK licenses.
# Avoid yes/SIGPIPE hiding sdkmanager failures under pipefail.
printf '%s\n' {1..100} | sed 's/.*/y/' | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
"$sdkmanager" --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
