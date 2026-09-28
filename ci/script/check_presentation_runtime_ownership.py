#!/usr/bin/env python3
"""Guard Host/Resident presentation ownership."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/AndroidInProcessPluginRuntimeAdapter.kt"
API = ROOT / "app/src/main/java/com/ai/limbs/plugin/runtime/InProcessPluginApi.kt"

adapter = ADAPTER.read_text(encoding="utf-8")
api = API.read_text(encoding="utf-8")
errors = []

for token in ("presentation_entry_class", "InProcessPluginPresentationEntry", "PRESENTATION_ENTRY_TYPE_INVALID"):
    if token in adapter:
        errors.append("LEGACY_HOST adapter must not mount PresentationEntry: " + token)

for token in ("InProcessHostLocalPresentationProviderFactory", "registerHostLocalPresentationProvider"):
    if token not in api:
        errors.append("Runtime API missing: " + token)

if "override fun registerHostLocalPresentationProvider(" not in adapter:
    errors.append("Runtime adapter does not implement Host-local presentation registration")
if "context.runtimeRole == PluginRuntimeRole.BUSINESS" not in adapter:
    errors.append("Runtime adapter does not keep Resident BUSINESS UI-free")
if "factory.create(this)" not in adapter:
    errors.append("LEGACY_HOST does not materialize Host-local presentation factory")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    raise SystemExit(1)

print("Presentation runtime ownership: PASS")
print("LEGACY_HOST PresentationEntry mounts: 0")
print("BUSINESS Host-local factory materializations: 0 by contract")
