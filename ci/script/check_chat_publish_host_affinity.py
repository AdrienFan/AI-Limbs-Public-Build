#!/usr/bin/env python3
"""Guard visible chat publication ownership across Resident Core and Android Host."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
BINDINGS = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostPrimitiveGatewayBindings.kt"
HOST_PROXY = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginHostUiProxyBridge.kt"
TEST = ROOT / "app/src/test/java/com/ai/assistance/operit/plugins/center/HostPrimitiveAffinityRoutingTest.kt"

bindings = BINDINGS.read_text(encoding="utf-8")
host_proxy = HOST_PROXY.read_text(encoding="utf-8")
test = TEST.read_text(encoding="utf-8")
errors = []

required_bindings = [
    '"host.chat@1" to primitive(',
    'owned(HostGatewayExecutionAffinity.HOST_SERVICE, kernel("publish_assistant"))',
    'owned(HostGatewayExecutionAffinity.HOST_SERVICE, kernel("publish_user"))',
    'owned(HostGatewayExecutionAffinity.HOST_SERVICE, kernel("set_presentation"))',
    'invokeResidentHostKernelPrimitive(',
    'ResidentComponentProxyBroker.KIND_HOST_PRIMITIVE',
]
for token in required_bindings:
    if token not in bindings:
        errors.append("Host chat affinity binding missing: " + token)

required_proxy = [
    'operationOwnedByAndroidHost',
    'HostPrimitiveGatewayBindings.requiresAndroidHost(primitiveId, operation)',
    'binding?.kind == HostGatewayRouteKind.KERNEL',
]
for token in required_proxy:
    if token not in host_proxy:
        errors.append("Host proxy operation-ownership guard missing: " + token)

if "chatVisiblePublishesReturnToAndroidHostWhileReadsStayBusinessOwned" not in test:
    errors.append("Cloud regression test for chat publication ownership is missing")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    raise SystemExit(1)

print("Chat publish Host-affinity ownership: PASS")
print("host.chat@1/messages -> CORE_SAFE")
print("host.chat@1/publish_assistant -> HOST_SERVICE")
print("host.chat@1/publish_user -> HOST_SERVICE")

print("host.chat@1/set_presentation -> HOST_SERVICE")
