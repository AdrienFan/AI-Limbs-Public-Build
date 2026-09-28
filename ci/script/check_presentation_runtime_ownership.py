#!/usr/bin/env python3
"""Guard Host/Resident presentation ownership."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/AndroidInProcessPluginRuntimeAdapter.kt"
API = ROOT / "app/src/main/java/com/ai/limbs/plugin/runtime/InProcessPluginApi.kt"
TRANSPORT = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/ProviderContributionTransport.kt"
UI_PROXY = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginHostUiProxyBridge.kt"

adapter = ADAPTER.read_text(encoding="utf-8")
api = API.read_text(encoding="utf-8")
transport = TRANSPORT.read_text(encoding="utf-8")
ui_proxy = UI_PROXY.read_text(encoding="utf-8")
errors = []

for token in ("presentation_entry_class", "InProcessPluginPresentationEntry", "PRESENTATION_ENTRY_TYPE_INVALID"):
    if token in adapter:
        errors.append("LEGACY_HOST adapter must not mount PresentationEntry: " + token)

if "while BUSINESS runtime remains in Resident Core" not in api:
    errors.append("PresentationEntry API must remain Resident/UI-proxy scoped")

if "is InProcessChatModeExtensionProvider" not in transport or "PRESENTATION_METADATA" not in transport:
    errors.append("Chat-mode presentation providers must cross Resident boundary as metadata only")

if "ProviderProxyProtocol.PRESENTATION_METADATA" not in ui_proxy:
    errors.append("Host UI proxy must merge presentation metadata with Host-local payloads")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    raise SystemExit(1)

print("Presentation runtime ownership: PASS")
print("LEGACY_HOST PresentationEntry mounts: 0")
print("Chat-mode Resident transport: PRESENTATION_METADATA")
