#!/usr/bin/env python3
"""Static guard for the canonical runtime capability registry and owner router."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostPrimitiveCatalog.kt"
REGISTRY = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/CapabilityRegistry.kt"
KERNEL_ADAPTER = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/KernelHostPrimitiveAdapter.kt"
UI_PROXY_BRIDGE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginHostUiProxyBridge.kt"
OWNER_GUARDED_FILES = (KERNEL_ADAPTER, UI_PROXY_BRIDGE)
ROUTER = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/RuntimeCapabilityRouting.kt"
CAPABILITY_GATEWAY = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginHostCapabilityRegistry.kt"

CATALOG_ID_RE = re.compile(r'HostPrimitiveDefinition\(\s*\d+\s*,\s*"([^"]+)"')
REGISTRY_ENTRY_RE = re.compile(
    r'hostPrimitive\(\s*"([^"]+)"'
    r'(?:\s*,\s*CapabilityExecutionOwner\.([A-Z_]+))?\s*\)'
)
VALID_OWNERS = {"HOST", "BUSINESS", "PLUGIN_RUNTIME", "EXTERNAL_DAEMON"}
def duplicate_values(values: list[str]) -> list[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return sorted(duplicates)


def main() -> int:
    catalog_text = CATALOG.read_text(encoding="utf-8")
    registry_text = REGISTRY.read_text(encoding="utf-8")

    catalog_ids = [item.lower() for item in CATALOG_ID_RE.findall(catalog_text)]
    registry_matches = REGISTRY_ENTRY_RE.findall(registry_text)
    registry_ids = [item.lower() for item, _ in registry_matches]
    registry_owners = {
        item.lower(): (owner or "BUSINESS")
        for item, owner in registry_matches
    }

    errors: list[str] = []
    catalog_duplicates = duplicate_values(catalog_ids)
    registry_duplicates = duplicate_values(registry_ids)
    if catalog_duplicates:
        errors.append(f"duplicate Host Primitive ids: {catalog_duplicates}")
    if registry_duplicates:
        errors.append(f"duplicate CapabilityRegistry ids: {registry_duplicates}")

    missing = sorted(set(catalog_ids) - set(registry_ids))
    extra = sorted(set(registry_ids) - set(catalog_ids))
    if missing:
        errors.append(f"Host Primitive ids missing from CapabilityRegistry: {missing}")
    if extra:
        errors.append(f"CapabilityRegistry ids absent from Host Primitive catalog: {extra}")

    for capability_id in registry_ids:
        match = re.search(r"@([1-9]\d*)$", capability_id)
        if not match:
            errors.append(f"capability id has no numeric version suffix: {capability_id}")

    invalid_owners = {
        capability_id: owner
        for capability_id, owner in registry_owners.items()
        if owner not in VALID_OWNERS
    }
    if invalid_owners:
        errors.append(f"invalid execution owners: {invalid_owners}")

    host_owned = {
        capability_id
        for capability_id, owner in registry_owners.items()
        if owner == "HOST"
    }
    if not host_owned:
        errors.append("CapabilityRegistry has no HOST-owned descriptors")

    for path in OWNER_GUARDED_FILES:
        text = path.read_text(encoding="utf-8")
        if "HOST_OWNED_PRIMITIVES" in text:
            errors.append(
                f"{path.relative_to(ROOT)} reintroduced forbidden HOST_OWNED_PRIMITIVES"
            )

    ui_proxy_text = UI_PROXY_BRIDGE.read_text(encoding="utf-8")
    canonical_ui_proxy_guard = (
        "CapabilityRegistry.isOwnedBy(primitiveId, CapabilityExecutionOwner.HOST)"
    )
    if canonical_ui_proxy_guard not in ui_proxy_text:
        errors.append(
            "PluginHostUiProxyBridge does not validate Host ownership from CapabilityRegistry"
        )

    router_text = ROUTER.read_text(encoding="utf-8")
    gateway_text = CAPABILITY_GATEWAY.read_text(encoding="utf-8")
    kernel_text = KERNEL_ADAPTER.read_text(encoding="utf-8")
    for forbidden in ("MIGRATED_HOST_IDS", "fun isMigrated("):
        if forbidden in router_text:
            errors.append(f"RuntimeCapabilityRouter still contains staged capability routing: {forbidden}")
    if "RuntimeCapabilityRouter.forRuntime(" not in gateway_text:
        errors.append("PluginHostCapabilityRegistry does not route SystemHost calls through RuntimeCapabilityRouter")
    if "CapabilityRegistry.isOwnedBy(id, CapabilityExecutionOwner.HOST)" in kernel_text:
        errors.append("KernelHostPrimitiveAdapter still performs capability owner/process routing")
    router_required_tokens = (
        "CapabilityExecutionOwner.HOST to host",
        "CapabilityExecutionOwner.BUSINESS to local",
        "CapabilityExecutionOwner.PLUGIN_RUNTIME to",
        "CapabilityExecutionOwner.EXTERNAL_DAEMON to",
        "transports.resolve(descriptor.executionOwner)",
        "PluginRuntimeTransportAdapter",
        "ExternalDaemonTransportAdapter",
    )
    for token in router_required_tokens:
        if token not in router_text:
            errors.append(f"RuntimeCapabilityRouter is missing owner transport contract: {token}")

    required_tokens = (
        "val version: Int",
        "val executionOwner: CapabilityExecutionOwner",
        "val requestSchema: CapabilitySchemaRef",
        "val responseSchema: CapabilitySchemaRef",
        "val policy: CapabilityPolicyRef",
        "val maturity: HostPrimitiveMaturity",
        "val handlerAdapter: CapabilityHandlerAdapter",
        "fun find(id: String): CapabilityDescriptor?",
        "fun requireDescriptor(id: String): CapabilityDescriptor",
        "fun idsOwnedBy(owner: CapabilityExecutionOwner): Set<String>",
        "fun isOwnedBy(id: String, owner: CapabilityExecutionOwner): Boolean",
    )
    for token in required_tokens:
        if token not in registry_text:
            errors.append(f"CapabilityRegistry is missing queryable descriptor contract: {token}")

    if errors:
        print("Runtime capability registry check FAILED:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1
    owner_counts = {
        owner: sum(1 for value in registry_owners.values() if value == owner)
        for owner in sorted(VALID_OWNERS)
    }
    print(
        "Runtime capability registry check PASS: "
        f"{len(registry_ids)} descriptors, "
        f"{len(set(registry_ids))} unique ids, owners={owner_counts}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
