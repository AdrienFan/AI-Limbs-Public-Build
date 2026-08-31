#!/usr/bin/env python3
"""Static validation for declarative Plugin Lab sample packages."""

import json
from pathlib import Path
from zipfile import ZipFile

REPO = Path(__file__).resolve().parents[2]
OUTPUT = REPO / "app" / "src" / "pluginLab" / "assets" / "plugin_samples"
FORBIDDEN = {".apk", ".class", ".dex", ".jar", ".so"}

for package in sorted(OUTPUT.glob("*.ailp")):
    with ZipFile(package) as archive:
        names = set(archive.namelist())
        assert names == {"plugin.json", "runtime.json"}, (package, names)
        assert not any(Path(name).suffix.lower() in FORBIDDEN for name in names)
        manifest = json.loads(archive.read("plugin.json"))
        runtime = json.loads(archive.read("runtime.json"))

    assert manifest["format"] == "AIL_PLUGIN_V1"
    assert manifest["schema_version"] == 1
    assert manifest["api"] == {"target": 1, "min": 1}
    assert manifest["runtime"] == {"kind": "declarative", "entry": "runtime.json"}
    assert runtime["schema"] == 1

    declared_capabilities = set(manifest["provides"]["capabilities"])
    actual_capabilities = {item["id"] for item in runtime["capabilities"]}
    assert declared_capabilities == actual_capabilities

    declared_extensions = {
        (item["point"], item["id"]) for item in manifest["provides"]["extensions"]
    }
    actual_extensions = {
        (item["point"], item["id"]) for item in runtime["extensions"]
    }
    assert declared_extensions == actual_extensions
    print(f"validated {package.relative_to(REPO)}")
