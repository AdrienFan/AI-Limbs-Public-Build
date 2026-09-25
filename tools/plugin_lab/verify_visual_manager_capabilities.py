#!/usr/bin/env python3
"""Verify Visual Manager runtime capability registrations match plugin.json."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "plugin-lab/packages/visual-manager/plugin.json"
ENTRY = ROOT / "plugin-lab/plugins/visual-manager/src/main/java/com/ai/limbs/plugins/visualmanager/VisualManagerEntry.kt"

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
plugin_id = manifest["plugin_id"]
declared_list = manifest["provides"]["capabilities"]
declared = set(declared_list)

if len(declared_list) != len(declared):
    print("Visual Manager manifest contains duplicate capability declarations.", file=sys.stderr)
    sys.exit(1)

source = ENTRY.read_text(encoding="utf-8")
names = set(re.findall(r'\bcapability\(\s*"([^"]+)"', source))
runtime = {f"{plugin_id}.{name}" for name in names}

missing = sorted(runtime - declared)
extra = sorted(declared - runtime)
if missing or extra:
    if missing:
        print("Runtime capabilities missing from plugin.json:", file=sys.stderr)
        for item in missing:
            print(f"  - {item}", file=sys.stderr)
    if extra:
        print("Manifest capabilities not registered by runtime:", file=sys.stderr)
        for item in extra:
            print(f"  - {item}", file=sys.stderr)
    sys.exit(1)

print(f"Visual Manager capability declarations OK: {len(runtime)} capabilities.")
