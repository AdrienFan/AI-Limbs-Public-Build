#!/usr/bin/env python3
"""Static guard for canonical Provider transport across Worker -> Resident Core -> Host UI."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKER = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/PluginWorkerRuntime.kt"
REMOTE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/RemoteAndroidInProcessPluginRuntimeAdapter.kt"
TRANSPORT = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/ProviderContributionTransport.kt"
RUNTIME = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginRuntime.kt"
WIRE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/PluginRuntimeMain.kt"
KERNEL = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginPlatformKernel.kt"
UI_PROXY = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/PluginHostUiProxyBridge.kt"
TEST = ROOT / "app/src/test/java/com/ai/assistance/operit/plugins/center/ProviderContributionTransportTest.kt"
MAIN = ROOT / "app/src/main/java"

SYNTHETIC_ID = "plugin.test.unseen.provider_probe"


def main() -> int:
    errors: list[str] = []
    worker = WORKER.read_text(encoding="utf-8")
    remote = REMOTE.read_text(encoding="utf-8")
    transport = TRANSPORT.read_text(encoding="utf-8")
    runtime = RUNTIME.read_text(encoding="utf-8")
    wire = WIRE.read_text(encoding="utf-8")
    kernel = KERNEL.read_text(encoding="utf-8")
    ui_proxy = UI_PROXY.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")

    required = {
        "worker canonical envelope": (worker, "ProviderContributionTransportCodec.encode(record)"),
        "remote canonical decode": (remote, "ProviderContributionTransportCodec.decode"),
        "trusted canonical restore": (remote, "context.canonicalRestore.registerProvider(contract, payload)"),
        "restore owner binding": (runtime, "REMOTE_CONTRIBUTION_OWNER_MISMATCH"),
        "generic child installer protocol": (transport, 'CHILD_EXTENSION_INSTALLER("child_extension_installer.v1")'),
        "generic provider child install op": (wire, '"provider_child_install"'),
        "provider id carried on wire": (remote, '.put("provider_id", id)'),
        "Core UI canonical Provider export": (kernel, "ProviderContributionTransportCodec.encode(record)"),
        "Host UI canonical Provider decode": (ui_proxy, "ProviderContributionTransportCodec.decode(item)"),
        "Host UI generic child installer protocol": (ui_proxy, "ProviderProxyProtocol.CHILD_EXTENSION_INSTALLER"),
        "Host UI generic child install command": (ui_proxy, '.put("command", "provider_child_install")'),
        "Core generic child install command": (kernel, '"provider_child_install" ->'),
        "test-only unseen plugin identity": (test, SYNTHETIC_ID),
        "unseen Host path uses real Registrar": (test, "hostRegistrar.registerProvider(providerId, executor"),
        "unseen Resident path uses canonical restore": (test, "restore.registerProvider(envelope.contract, residentProxy)"),
    }
    for name, (haystack, token) in required.items():
        if token not in haystack:
            errors.append(f"{name} missing: {token}")

    forbidden_remote = (
        '"extension_hub"',
        "EXTENSION_HUB_PLUGIN_ID",
        "EXTENSION_HUB_PROVIDER",
        "registrar.registerProvider(",
    )
    for token in forbidden_remote:
        if token in remote:
            errors.append(f"Remote Provider restore still contains plugin-specific/duplicate validation token: {token}")

    if '.put("kind", "extension_hub")' in worker:
        errors.append("Worker Provider export still emits extension_hub wire kind")

    for name, source in (("Core UI Provider mirror", kernel), ("Host UI Provider mirror", ui_proxy)):
        for token in ('"extension_hub"', '"extension_hub_install"', "EXTENSION_HUB_PLUGIN_ID", "EXTENSION_HUB_PROVIDER"):
            if token in source:
                errors.append(f"{name} still contains plugin-specific Provider token: {token}")

    if SYNTHETIC_ID not in test:
        errors.append("Synthetic unseen plugin identity test is missing")
    for path in MAIN.rglob("*.kt"):
        if SYNTHETIC_ID in path.read_text(encoding="utf-8"):
            errors.append(f"Synthetic test plugin ID leaked into production source: {path.relative_to(ROOT)}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Canonical Provider transport: PASS")
    print("Remote concrete plugin/provider identity checks: 0")
    print("Resident UI concrete plugin/provider identity checks: 0")
    print("Synthetic unseen plugin identity remains test-only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
