#!/usr/bin/env python3
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
manifest_path = ROOT / "plugin-lab/packages/extension-hub/plugin.json"
entry_path = ROOT / "plugin-lab/plugins/extension-hub/src/main/java/com/ai/limbs/plugins/extensionhub/ExtensionHubEntry.kt"
sdk_path = ROOT / "plugin-lab/sdk/inprocess-api/src/main/java/com/ai/limbs/plugin/runtime/InProcessPluginApi.kt"

manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
entry = entry_path.read_text(encoding="utf-8")
sdk = sdk_path.read_text(encoding="utf-8")
errors = []

if manifest.get("version") != "1.5.5":
    errors.append("Extension Hub manifest is not 1.5.5")
provides = manifest.get("provides", {})
if provides.get("services") != ["system.extension.hub"]:
    errors.append("Extension Hub must publish system.extension.hub as a canonical Service")
if provides.get("providers"):
    errors.append("Extension Hub must not publish the legacy installer Provider")
if "host.registerService(" not in entry or 'EXTENSION_HUB_SERVICE_ID = "system.extension.hub"' not in entry:
    errors.append("Extension Hub entry does not register the canonical Service")
for forbidden in ("host.registerProvider(", "EXTENSION_HUB_PROVIDER", ": ExtensionHubService"):
    if forbidden in entry:
        errors.append(f"Extension Hub reintroduced legacy Provider adapter: {forbidden}")
for required in ("fun interface InProcessServiceEndpoint", "fun registerService("):
    if required not in sdk:
        errors.append(f"Plugin SDK missing canonical Service API: {required}")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    raise SystemExit(1)

print("Extension Hub canonical Service: PASS")
print("Service: system.extension.hub@1")
print("Legacy installer Provider: absent")
