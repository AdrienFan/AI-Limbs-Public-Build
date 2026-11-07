#!/usr/bin/env python3
"""Validate the installable identity and the isolated CI packaging contract."""
import ast
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
module = ROOT / "plugin-lab/plugins/privacy-audit"
manifest = json.loads((ROOT / "plugin-lab/packages/privacy-audit/plugin.json").read_text())
assert manifest["plugin_id"] == "plugin.privacy_audit"
assert manifest["version"] == "0.1.0"
assert manifest["runtime"]["kind"] == "android_inprocess"
assert manifest["runtime"]["entry"] == "payload/plugin.apk"
assert set(manifest["permissions"]["requested_scopes"]) == {"host.process@1", "host.attention@1", "host.notification@1"}
assert len(manifest["provides"]["capabilities"]) == len(set(manifest["provides"]["capabilities"])) == 7
source_dir = module / "src/main/java/com/ai/limbs/plugins/privacyaudit"
entry_source = (source_dir / "AuditEntry.kt").read_text()
for key in ("entry_class", "presentation_entry_class"):
    name = manifest["runtime"]["config"][key].split(".")[-1]
    assert f"class {name} :" in entry_source
gradle = (module / "build.gradle.kts").read_text()
assert 'versionName = "0.1.0"' in gradle
assert 'applicationId = "com.ai.limbs.payload.privacyaudit.v010"' in gradle
assert 'compileOnly(project(":plugin-inprocess-api"))' in gradle
assert 'assets.srcDir("../../packages/privacy-audit")' in gradle
android = ET.parse(module / "src/main/AndroidManifest.xml").getroot()
assert not android.findall("uses-permission"), "Payload must not independently acquire Android permissions"
assert not android.findall("application/service"), "No second VPN or unmanaged service"
assert not android.findall("application/activity"), "UI is contributed through the existing plugin runtime"
shell = (source_dir / "ShellCommands.java").read_text()
assert '"pm "+verb' in shell and '"disable-user"' in shell and '"default-state"' in shell
assert not re.search(r'\b(su|iptables|ip6tables|tcpdump)\b', shell)
settings = (ROOT / "privacy-audit-build/settings.gradle.kts").read_text()
assert 'include(":plugin-inprocess-api", ":privacy-audit-plugin")' in settings
for script in ("package_privacy_audit.py", "validate_privacy_audit.py"):
    ast.parse((ROOT / "tools/plugin_lab" / script).read_text())
# SQLite PRAGMA queries may return result rows. execSQL() rejects result-producing statements at runtime.
store_source = (source_dir / "AuditStore.kt").read_text()
assert not re.search(r'\.execSQL\s*\(\s*"PRAGMA\b', store_source, re.IGNORECASE)
assert 'db.enableWriteAheadLogging()' in store_source
assert 'db.rawQuery("PRAGMA secure_delete=ON", null)' in store_source

print("PASS: plugin identity, ABI declarations, isolated build, manifest and signing script contracts")
