#!/usr/bin/env python3
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "dist"
OUTPUT = DIST / "AI-Limbs-Official-Plugins-for-v0.7.2.zip"

ITEMS = [
    ("plugin-lab/packages/extension-hub/plugin.json", "plugin-extension-hub.ailp", "Parent-Plugins"),
    ("plugin-lab/packages/bridge-core/plugin.json", "bridge-core.ailp", "Parent-Plugins"),
    ("plugin-lab/packages/developer-guide/plugin.json", "developer-guide.ailp", "Parent-Plugins"),
    ("plugin-lab/packages/packager/plugin.json", "ai-limbs-packager.ailp", "Parent-Plugins"),
    ("plugin-lab/packages/rdc/extension.json", "rdc-provider.ailx", "Child-Extensions"),
    ("plugin-lab/packages/triggercmd/extension.json", "triggercmd-provider.ailx", "Child-Extensions"),
]
def load_meta(path: str):
    root = json.loads((ROOT / path).read_text(encoding="utf-8"))
    display = root.get("display", {})
    name = display.get("name") or root.get("plugin_id") or root.get("extension_id")
    version = root["version"]
    return name, version, root


def safe_name(value: str) -> str:
    forbidden = '\\/:*?"<>|'
    return "".join("-" if ch in forbidden else ch for ch in value).strip() or "AI Limbs Plugin"


def main() -> None:
    if OUTPUT.exists():
        OUTPUT.unlink()
    suite_items = []
    with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for meta_path, source_name, group in ITEMS:
            source = DIST / source_name
            if not source.is_file():
                raise SystemExit(f"Missing packaged plugin: {source}")
            name, version, root = load_meta(meta_path)
            suffix = source.suffix
            final_name = f"{safe_name(name)} v{version}{suffix}"
            archive.write(source, f"{group}/{final_name}")
            suite_items.append({
                "name": name,
                "version": version,
                "file": f"{group}/{final_name}",
                "id": root.get("plugin_id") or root.get("extension_id"),
            })
        archive.writestr(
            "suite.json",
            json.dumps({"target_host": "0.7.2", "items": suite_items}, ensure_ascii=False, indent=2),
        )
    print(OUTPUT)


if __name__ == "__main__":
    main()
