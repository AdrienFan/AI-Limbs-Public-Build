#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main"
TEST = ROOT / "app/src/test"
UNKNOWN = "plugin.test.unknown_abc"

errors = []

main_hits = []
for path in MAIN.rglob("*"):
    if path.is_file() and path.suffix in {".kt", ".java", ".xml", ".json"}:
        text = path.read_text(encoding="utf-8", errors="ignore")
        if UNKNOWN in text:
            main_hits.append(str(path.relative_to(ROOT)))
if main_hits:
    errors.append("unknown acceptance plugin leaked into production source: " + ", ".join(main_hits))

test_path = TEST / "java/com/ai/assistance/operit/plugins/center/UnknownPluginUnifiedReceiverTest.kt"
if not test_path.is_file():
    errors.append("UnknownPluginUnifiedReceiverTest.kt is missing")
else:
    text = test_path.read_text(encoding="utf-8")
    for token in (
        UNKNOWN,
        "ProviderContributionTransportCodec",
        "ServiceContributionTransportCodec",
        "ExtensionContributionTransportCodecRegistry",
        "CanonicalChildDescriptorEnvelopeCodec",
        "PluginContributionKind.CAPABILITY",
        "extension.test.unknown_child",
    ):
        if token not in text:
            errors.append("unknown-plugin acceptance test missing: " + token)

forbidden = (
    "EXTENSION_HUB_COMPAT_SERVICE_ID",
    "interface ExtensionHubService",
    "ProviderProxyProtocol.CHILD_EXTENSION_INSTALLER",
    'CHILD_EXTENSION_INSTALLER("child_extension_installer.v1")',
    '"provider_child_install"',
)
for path in (ROOT / "app/src/main").rglob("*.kt"):
    text = path.read_text(encoding="utf-8", errors="ignore")
    for token in forbidden:
        if token in text:
            errors.append(f"legacy receiver debt reintroduced: {token} in {path.relative_to(ROOT)}")

isolation = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/isolation"
for path in isolation.rglob("*.kt"):
    text = path.read_text(encoding="utf-8", errors="ignore").lower()
    for token in ("plugin.system.extension_hub", "system.extension.hub", "extension_hub"):
        if token in text:
            errors.append(f"Remote/Worker concrete Hub identity reintroduced: {token} in {path.relative_to(ROOT)}")

legacy_file = ROOT / "app/src/main/java/com/ai/assistance/operit/plugins/center/ChildExtensionRuntime.kt"
legacy_text = legacy_file.read_text(encoding="utf-8")
if legacy_text.count('LEGACY_EXTENSION_HUB_PLUGIN_ID = "plugin.system.extension_hub"') != 1:
    errors.append("legacy disk migration ID must remain exactly one isolated compatibility marker")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    raise SystemExit(1)

print("Unknown plugin unified receiver: PASS")
print("Production source knowledge of plugin.test.unknown_abc: 0")
print("Remote/Worker concrete Extension Hub identity: 0")
print("Legacy Hub Provider/compat Service protocol: absent")
