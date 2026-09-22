#!/usr/bin/env python3
"""Guard the unified Host action path for Bridge child runtimes."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRIB = ROOT / "plugin-lab/sdk/bridge-contract/src/main/java/com/ai/assistance/operit/integrations/ailimbs/BridgeProviderContribution.kt"
FACTORY = ROOT / "plugin-lab/sdk/bridge-contract/src/main/java/com/ai/assistance/operit/integrations/ailimbs/BridgeProviderFactory.kt"
MANAGER = ROOT / "plugin-lab/plugins/bridge-core/src/main/java/com/ai/assistance/operit/integrations/ailimbs/PluginBridgeManager.kt"
ENTRY = ROOT / "plugin-lab/plugins/bridge-core/src/main/java/com/ai/limbs/plugins/bridge/BridgePluginEntry.kt"

CHILDREN = {
    "rdc": ROOT / "plugin-lab/packages/rdc/extension.json",
    "sentinelx": ROOT / "plugin-lab/packages/sentinelx/extension.json",
    "triggercmd": ROOT / "plugin-lab/packages/triggercmd/extension.json",
}
RUNTIME_DIRS = [
    ROOT / "plugin-lab/extensions/rdc",
    ROOT / "plugin-lab/extensions/sentinelx",
    ROOT / "plugin-lab/extensions/triggercmd",
]

errors: list[str] = []

def require(path: Path, token: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if token not in text:
        errors.append(f"{label} missing: {token}")

require(CONTRIB, "suspend fun perform(action: BridgeAction): Boolean", "BridgeProviderControl suspend action")
require(FACTORY, "suspend fun openAuthorizationPage(): Boolean", "Bridge provider suspend Host effect")
require(MANAGER, "suspend fun perform(action: BridgeAction, providerId: String? = null): Boolean", "Bridge manager suspend action")
require(ENTRY, "apiVersion = 5", "Bridge provider point API")
require(ENTRY, 'allowedHostCapabilities = setOf("host.android.component@1")', "Bridge parent host delegation")

bridge_manifest = json.loads((ROOT / "plugin-lab/packages/bridge-core/plugin.json").read_text(encoding="utf-8"))
scopes = set(bridge_manifest.get("permissions", {}).get("requested_scopes", []))
if "host.android.component@1" not in scopes:
    errors.append("Bridge parent does not request host.android.component@1")

for name, path in CHILDREN.items():
    data = json.loads(path.read_text(encoding="utf-8"))
    target = data.get("target", {})
    if target.get("extension_point") != "ai_limbs.bridge.provider" or target.get("api") != 5:
        errors.append(f"{name} child does not target ai_limbs.bridge.provider@5")
    if name in {"rdc", "sentinelx"}:
        caps = set(data.get("permissions", {}).get("host_capabilities", []))
        if "host.android.component@1" not in caps:
            errors.append(f"{name} child does not request host.android.component@1")

for directory in RUNTIME_DIRS:
    for path in directory.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        if "startActivity(" in text:
            errors.append(f"Direct Activity side effect bypasses unified Host entry: {path.relative_to(ROOT)}")

panel_files = [
    ROOT / "plugin-lab/extensions/sentinelx/src/main/java/com/ai/limbs/extensions/sentinelx/SentinelXBridgeProviderPanel.kt",
    ROOT / "plugin-lab/extensions/triggercmd/src/main/java/com/ai/limbs/extensions/triggercmd/TriggerCmdBridgeProviderPanel.kt",
]
for path in panel_files:
    text = path.read_text(encoding="utf-8")
    if "private suspend fun performBridgeAction(" not in text:
        errors.append(f"Bridge action helper lost suspend semantics: {path.relative_to(ROOT)}")
    if "private fun performBridgeAction(" in text:
        errors.append(f"Bridge action helper bypasses suspend control: {path.relative_to(ROOT)}")

rdc = (ROOT / "plugin-lab/extensions/rdc/src/main/java/com/ai/limbs/extensions/rdc/runtime/AiLimbsRdcClient.kt").read_text(encoding="utf-8")
sentinel = (ROOT / "plugin-lab/extensions/sentinelx/src/main/java/com/ai/limbs/extensions/sentinelx/runtime/SentinelXBridgeProvider.kt").read_text(encoding="utf-8")
for label, text in (("RDC", rdc), ("SentinelX", sentinel)):
    if "invokeHostCapability(" not in text or '"host.android.component@1"' not in text:
        errors.append(f"{label} Host effect does not use unified Host capability entry")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    raise SystemExit(1)

print("Unified Bridge Host action path: PASS")
print("Bridge provider point: ai_limbs.bridge.provider@5")
print("Direct startActivity in Bridge child runtimes: 0")
