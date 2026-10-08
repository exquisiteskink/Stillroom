#!/usr/bin/env bash
set -euo pipefail
# Avoid another privileged Docker operation when the mise-started fixtures are already listening.
# Preparation verifies the authenticated version and both users before any Android test runs.
for port in 9283 9284; do
  status=$(curl --silent --max-time 3 --output /dev/null --write-out '%{http_code}' "http://127.0.0.1:$port/api/system/info" || true)
  if [[ "$status" != 401 ]]; then
    mise run grocy:up
    break
  fi
done
