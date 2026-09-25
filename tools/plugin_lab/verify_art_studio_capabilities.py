#!/usr/bin/env python3
"""Fail CI when literal Art Studio runtime capabilities are absent from plugin.json."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "plugin-lab/packages/art-studio/plugin.json"
ENTRY = ROOT / "plugin-lab/plugins/art-studio/src/main/java/com/ai/limbs/plugins/artstudio/ArtStudioEntry.kt"

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
plugin_id = manifest["plugin_id"]
declared_list = manifest["provides"]["capabilities"]
declared = set(declared_list)

if len(declared) != len(declared_list):
    print("Art Studio manifest contains duplicate capability declarations.", file=sys.stderr)
    sys.exit(1)

source = ENTRY.read_text(encoding="utf-8")
literal_names = {
    name
    for name in re.findall(r'\bcapability\(\s*"([^"]+)"', source)
    if "$" not in name
}
runtime_literals = {f"{plugin_id}.{name}" for name in literal_names}
missing = sorted(runtime_literals - declared)
if missing:
    print("Art Studio runtime registers capabilities missing from plugin.json:", file=sys.stderr)
    for capability_id in missing:
        print(f"  - {capability_id}", file=sys.stderr)
    sys.exit(1)

wrong_prefix = sorted(
    capability_id
    for capability_id in declared
    if not capability_id.startswith(f"{plugin_id}.")
)
if wrong_prefix:
    print("Art Studio manifest has capabilities outside its plugin namespace:", file=sys.stderr)
    for capability_id in wrong_prefix:
        print(f"  - {capability_id}", file=sys.stderr)
    sys.exit(1)

print(
    f"Art Studio capability declarations OK: "
    f"{len(runtime_literals)} literal runtime registrations covered; "
    f"{len(declared)} manifest declarations total."
)
