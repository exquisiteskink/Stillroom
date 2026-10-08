#!/usr/bin/env python3
"""Redact ephemeral fixture keys even if an Android assertion accidentally includes them."""
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
document = json.loads(path.read_text()) if path.exists() else {}
keys = [value for fixture in document.get("fixtures", []) for name, value in fixture.items() if name.endswith("_key") and isinstance(value, str)]
for line in sys.stdin:
    for key in keys:
        line = line.replace(key, "[redacted fixture key]")
    print(line, end="", flush=True)
