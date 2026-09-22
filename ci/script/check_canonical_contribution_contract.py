#!/usr/bin/env python3
"""Static guard for the canonical plugin contribution contract."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/CanonicalContributionContract.kt"
RUNTIME = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginRuntime.kt"
MAIN = ROOT / "app/src/main/java"


def main() -> int:
    errors: list[str] = []
    contract_text = CONTRACT.read_text(encoding="utf-8")
    runtime_text = RUNTIME.read_text(encoding="utf-8")

    required_contract_tokens = (
        "data class CanonicalContributionContract internal constructor(",
        "val schemaVersion: Int,",
        "val kind: PluginContributionKind,",
        "val contractType: PluginContributionContractType,",
        "val ownerPluginId: String,",
        "val id: String,",
        "val apiVersion: Int?,",
        "val metadata: Map<String, String>",
        "internal object PluginContributionContractCodec",
        "const val SCHEMA_VERSION = 1",
        '.put("contract_type", contract.contractType.wireName)',
        '.put("owner", contract.ownerPluginId)',
    )
    for token in required_contract_tokens:
        if token not in contract_text:
            errors.append(f"canonical contribution contract token missing: {token}")

    forbidden_plugin_ids = (
        "plugin.system.extension_hub",
        "plugin.system.bridge",
        "plugin.system.environment_center",
        "plugin.system.log_center",
    )
    for plugin_id in forbidden_plugin_ids:
        if plugin_id in contract_text:
            errors.append(f"canonical contribution contract must not name concrete plugin: {plugin_id}")

    record_match = re.search(
        r"data class PluginContributionRecord\(\s*(.*?)\s*\)\s*\{",
        runtime_text,
        flags=re.S,
    )
    if not record_match:
        errors.append("PluginContributionRecord declaration not found")
    else:
        params = record_match.group(1)
        if "val contract: CanonicalContributionContract" not in params:
            errors.append("PluginContributionRecord must own CanonicalContributionContract")
        if "val payload: Any?" not in params:
            errors.append("PluginContributionRecord must own payload")
        for legacy in ("ownerPluginId", "kind:", "id:", "apiVersion", "extensionPoint", "metadata:"):
            if legacy in params and legacy != "":
                errors.append(f"PluginContributionRecord reintroduced duplicate canonical field: {legacy}")

    registrar_tokens = (
        "private fun validatedContract(",
        "PluginContributionKind.CAPABILITY ->",
        "PluginContributionKind.SERVICE ->",
        "PluginContributionKind.PROVIDER ->",
        "PluginContributionKind.EXTENSION ->",
        "surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_CAPABILITY)",
        "surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_SERVICE)",
        "surfacePolicy.requireAllowed(PluginSurfaceIds.PUBLISH_PROVIDER)",
        "requireDeclared(kind, normalizedId)",
        "CONTRIBUTION_OWNER_MISMATCH",
        "EXTENSION_API_MISMATCH",
    )
    for token in registrar_tokens:
        if token not in runtime_text:
            errors.append(f"PluginRegistrar canonical validation regressed: {token}")

    legacy_patterns = (
        re.compile(r"PluginContributionRecord\(\s*ownerPluginId\s*=", re.S),
        re.compile(r"PluginContributionRecord\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*,\s*PluginContributionKind\.", re.S),
    )
    for path in MAIN.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for pattern in legacy_patterns:
            if pattern.search(text):
                errors.append(f"legacy PluginContributionRecord construction remains in {path.relative_to(ROOT)}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Canonical contribution contract: PASS")
    print("Record source of truth: contract + payload")
    print("Concrete plugin IDs in contract core: 0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
