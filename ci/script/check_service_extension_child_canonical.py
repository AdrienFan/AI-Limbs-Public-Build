#!/usr/bin/env python3
"""Static guard for Stage C Service + Extension + Child canonical transport."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKER = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/PluginWorkerRuntime.kt"
REMOTE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/RemoteAndroidInProcessPluginRuntimeAdapter.kt"
WIRE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/PluginRuntimeMain.kt"
SERVICE = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/ServiceContributionTransport.kt"
EXTENSION = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/ExtensionContributionTransport.kt"
CHILD = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/CanonicalChildDescriptor.kt"
CHILD_RUNTIME = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/ChildExtensionRuntime.kt"
REMOTE_CHILD = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation/RemoteChildExtensionRuntimeOwner.kt"
SDK = ROOT / "app/src/main/java/com/ai/limbs/plugin/runtime/InProcessPluginApi.kt"
TEST = ROOT / "app/src/test/java/com/ai/assistance/operit/plugins/center/ServiceExtensionChildCanonicalTest.kt"
MAIN = ROOT / "app/src/main/java"

SYNTHETIC_IDS = (
    "plugin.test.unseen.stagec",
    "plugin.test.future.parent",
    "extension.test.future.child",
)


def main() -> int:
    errors: list[str] = []
    worker = WORKER.read_text(encoding="utf-8")
    remote = REMOTE.read_text(encoding="utf-8")
    wire = WIRE.read_text(encoding="utf-8")
    service = SERVICE.read_text(encoding="utf-8")
    extension = EXTENSION.read_text(encoding="utf-8")
    child = CHILD.read_text(encoding="utf-8")
    child_runtime = CHILD_RUNTIME.read_text(encoding="utf-8")
    remote_child = REMOTE_CHILD.read_text(encoding="utf-8")
    sdk = SDK.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")

    required = {
        "SDK generic Service publication": (sdk, "fun registerService("),
        "Worker Service canonical export": (worker, "ServiceContributionTransportCodec.encode(record)"),
        "Worker Service snapshot": (worker, '.put("services", serviceDescriptors(pluginId))'),
        "Service RPC wire operation": (wire, '"service_invoke" ->'),
        "Remote Service canonical decode": (remote, "ServiceContributionTransportCodec.decode"),
        "Remote Service trusted restore": (remote, "context.canonicalRestore.registerService(contract, proxy)"),
        "Extension codec registry": (extension, "ExtensionContributionTransportCodecRegistry"),
        "UI_SCREEN protocol version": (extension, 'UI_SCREEN("ui.screen@2"'),
        "THEME protocol version": (extension, 'THEME("ui.theme@1"'),
        "Worker Extension registry encode": (worker, "ExtensionContributionTransportCodecRegistry.encode(record)"),
        "Remote Extension registry decode": (remote, "ExtensionContributionTransportCodecRegistry.decode"),
        "Remote Extension trusted restore": (remote, "context.canonicalRestore.registerExtension"),
        "Child canonical target": (child, "val target: ChildExtensionTarget"),
        "Child parent point descriptor": (child_runtime, "CanonicalChildDescriptors.parentPoint"),
        "Child binding descriptor": (child_runtime, "CanonicalChildDescriptors.childBinding"),
        "Child capability descriptor": (child_runtime, "CanonicalChildDescriptors.capability"),
        "Child UI descriptor": (child_runtime, "CanonicalChildDescriptors.ui"),
        "Worker unified child descriptors": (worker, '.put("descriptors", descriptors)'),
        "Remote unified child descriptors": (remote_child, 'value.optJSONArray("descriptors")'),
        "Synthetic unknown identities test": (test, SYNTHETIC_IDS[0]),
        "Arbitrary future point test": (test, '"vendor.future.slot"'),
    }
    for name, (source, token) in required.items():
        if token not in source:
            errors.append(f"{name} missing: {token}")

    old_extension_wire = (
        '.put("kind", "home_tile")',
        '.put("kind", "screen")',
        '.put("kind", "theme")',
        '"home_tile" ->',
        '"screen" ->',
        '"theme" ->',
    )
    for token in old_extension_wire:
        if token in worker or token in remote:
            errors.append(f"Duplicated Extension wire branch still present: {token}")

    old_child_wire = (
        '.put("ui_contributions"',
        'optJSONArray("ui_contributions")',
    )
    for token in old_child_wire:
        if token in worker or token in remote_child:
            errors.append(f"Legacy Child contribution wire still present: {token}")

    for source_name, source in (
        ("Service transport", service),
        ("Extension transport", extension),
        ("Child descriptor", child),
    ):
        for forbidden in ("plugin.system.extension_hub", "plugin.system.bridge", "plugin.system.environment_center"):
            if forbidden in source:
                errors.append(f"{source_name} contains concrete plugin identity: {forbidden}")

    for synthetic in SYNTHETIC_IDS:
        if synthetic not in test:
            errors.append(f"Synthetic test identity missing: {synthetic}")
        for path in MAIN.rglob("*.kt"):
            if synthetic in path.read_text(encoding="utf-8"):
                errors.append(f"Synthetic identity leaked into production source: {synthetic} in {path.relative_to(ROOT)}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Stage C Service/Extension/Child canonical transport: PASS")
    print("Worker/Remote duplicate Extension kind branches: 0")
    print("Legacy Child contribution wire keys: 0")
    print("Concrete plugin identities in Stage C protocol core: 0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
