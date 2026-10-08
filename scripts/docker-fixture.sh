#!/usr/bin/env bash
set -euo pipefail
# Omarchy may require elevation rather than membership in the docker group.
if [[ -w /var/run/docker.sock ]]; then
  exec docker "$@"
else
  exec pkexec /usr/bin/docker "$@"
fi
