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

page = (ROOT / "plugin-lab/plugins/visual-manager/src/main/java/com/ai/limbs/plugins/visualmanager/VisualManagerPageProvider.kt").read_text(encoding="utf-8")
actions = (ROOT / "plugin-lab/plugins/visual-manager/src/main/java/com/ai/limbs/plugins/visualmanager/VisualManagerPageActions.kt").read_text(encoding="utf-8")
controller = (ROOT / "plugin-lab/plugins/visual-manager/src/main/java/com/ai/limbs/plugins/visualmanager/VisualManagerController.kt").read_text(encoding="utf-8")
if "invokeHostCapability" in page or "invokeHostCapability" in actions:
    print("Visual Manager presentation/page must not invoke Host primitives directly.", file=sys.stderr)
    sys.exit(1)
if "invokePluginCapability" not in actions:
    print("Visual Manager presentation must route UI actions through Core-owned plugin capabilities.", file=sys.stderr)
    sys.exit(1)
if "invokeHostCapability" not in controller:
    print("Visual Manager Core controller must own Host primitive invocation.", file=sys.stderr)
    sys.exit(1)

print(f"Visual Manager capability declarations OK: {len(runtime)} capabilities; presentation routing OK.")
