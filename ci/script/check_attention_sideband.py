#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
catalog = (root / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostPrimitiveCatalog.kt").read_text()
gateway = (root / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostPrimitiveGatewayBindings.kt").read_text()
kernel = (root / "app/src/main/java/com/ai/assistance/operit/plugins/center/KernelHostPrimitiveAdapter.kt").read_text()
registry = (root / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostAttentionRegistry.kt").read_text()
dispatcher = (root / "app/src/main/java/com/ai/assistance/operit/integrations/ailimbs/AiLimbsDispatcher.kt").read_text()
rdc = (root / "app/src/main/java/com/ai/assistance/operit/integrations/ailimbs/AiLimbsRdcToolAdapter.kt").read_text()

required = {
    "catalog": ('"host.attention@1"', catalog),
    "gateway": ('"host.attention@1" to primitive(HostGatewayExecutionAffinity.CORE_SAFE', gateway),
    "kernel publish": ('"host.attention@1/publish"', kernel),
    "kernel clear": ('"host.attention@1/clear"', kernel),
    "host-bound source": ('HostAttentionRegistry.publish(ownerPluginId, parameters)', kernel),
    "sparse zero filter": ('if (count <= 0) continue', registry),
    "dispatcher sideband": ('HostAttentionRegistry.attachTo(result)', dispatcher),
    "RDC process sideband": ('HostAttentionRegistry.attachTo(result)', rdc),
}
errors = [name for name, (token, text) in required.items() if token not in text]

for forbidden in ("plugin.chat.laner_bridge", "LanerChatPriority", "LanerChatMessageStatus"):
    if forbidden in registry or forbidden in dispatcher:
        errors.append(f"base attention layer contains plugin business token: {forbidden}")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    sys.exit(1)

print("Generic Attention sideband ownership: PASS")
print("plugin business -> host.attention@1 -> generic registry -> dispatcher sideband")
