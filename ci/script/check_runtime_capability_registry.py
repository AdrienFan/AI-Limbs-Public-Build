#!/usr/bin/env python3
"""Static guard for the Arch Test 3 runtime capability registry."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/HostPrimitiveCatalog.kt"
REGISTRY = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/CapabilityRegistry.kt"
LEGACY_OWNER_FILES = (
    ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/KernelHostPrimitiveAdapter.kt",
    ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginHostUiProxyBridge.kt",
)
OWNER_CONSUMERS = (
    (
        LEGACY_OWNER_FILES[0],
        "CapabilityRegistry.isOwnedBy(id, CapabilityExecutionOwner.HOST)",
        "id in HOST_OWNED_PRIMITIVES",
    ),
    (
        LEGACY_OWNER_FILES[1],
        "CapabilityRegistry.isOwnedBy(primitiveId, CapabilityExecutionOwner.HOST)",
        "primitiveId in HOST_OWNED_PRIMITIVES",
    ),
)

CATALOG_ID_RE = re.compile(r'HostPrimitiveDefinition\(\s*\d+\s*,\s*"([^"]+)"')
REGISTRY_ENTRY_RE = re.compile(
    r'hostPrimitive\(\s*"([^"]+)"'
    r'(?:\s*,\s*CapabilityExecutionOwner\.([A-Z_]+))?\s*\)'
)
LEGACY_OWNER_RE = re.compile(
    r'HOST_OWNED_PRIMITIVES\s*=\s*setOf\((.*?)\)',
    re.DOTALL,
)
STRING_RE = re.compile(r'"([^"]+)"')
VALID_OWNERS = {"HOST", "BUSINESS", "PLUGIN_RUNTIME", "EXTERNAL_DAEMON"}
def duplicate_values(values: list[str]) -> list[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return sorted(duplicates)


def extract_legacy_owner_ids(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    matches = LEGACY_OWNER_RE.findall(text)
    if len(matches) != 1:
        raise ValueError(
            f"{path.relative_to(ROOT)} must contain exactly one HOST_OWNED_PRIMITIVES set"
        )
    return {item.strip().lower() for item in STRING_RE.findall(matches[0])}


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
    for path in LEGACY_OWNER_FILES:
        try:
            legacy_ids = extract_legacy_owner_ids(path)
        except ValueError as error:
            errors.append(str(error))
            continue
        if legacy_ids != host_owned:
            errors.append(
                f"{path.relative_to(ROOT)} HOST_OWNED_PRIMITIVES={sorted(legacy_ids)} "
                f"does not mirror CapabilityRegistry HOST ids={sorted(host_owned)}"
            )

    for path, canonical_token, legacy_routing_token in OWNER_CONSUMERS:
        text = path.read_text(encoding="utf-8")
        if canonical_token not in text:
            errors.append(
                f"{path.relative_to(ROOT)} does not consume canonical owner metadata"
            )
        if legacy_routing_token in text:
            errors.append(
                f"{path.relative_to(ROOT)} still routes directly from the legacy owner mirror"
            )

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
