#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostLoggingService.kt"
text = SOURCE.read_text(encoding="utf-8")

required = (
    "pluginStore.listPluginIds() + runtimeById.keys",
    "snapshot?.persistentState ?: states.read(pluginId)",
    "snapshot?.activeManifest",
    "pluginSources?.invoke().orEmpty()",
)
forbidden = (
    "if (runtimeSnapshots != null)",
    "Bootstrap/test fallback only",
)

errors = []
errors += [token for token in required if token not in text]
errors += [token for token in forbidden if token in text]

if errors:
    for token in errors:
        print("ERROR: logging inventory parity invariant failed:", token, file=sys.stderr)
    raise SystemExit(1)

print("Logging inventory parity: PASS")
print("Persistent PluginStore inventory is merged with runtime snapshots")
