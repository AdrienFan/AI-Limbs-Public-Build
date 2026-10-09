#!/usr/bin/env python3
"""Package a blank self module. No Android/Kotlin compilation occurs here."""
import argparse
import hashlib
import json
import uuid
import zipfile
from pathlib import Path

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--identity", help="Existing identity UUID for upgrades; omitted creates a new identity")
    parser.add_argument("--version", default="0.1.3")
    parser.add_argument("--without-presentation", action="store_true", help="Package legacy V0.1 lifecycle-only fixtures")
    args = parser.parse_args()
    identity = str(uuid.UUID(args.identity)) if args.identity else str(uuid.uuid4())
    if args.output.suffix.lower() != ".ails":
        parser.error("output must end with .ails")
    if len(args.version.split(".")) != 3 or not all(part.isdigit() for part in args.version.split(".")):
        parser.error("version must be major.minor.patch")
    source = Path(__file__).resolve().parents[1] / "self-module" / "blank.json"
    program = json.loads(source.read_text(encoding="utf-8"))
    if program["entry"] != "blank":
        parser.error("V0.1 accepts only the blank entry")
    program["module_version"] = args.version
    payload = json.dumps(program, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    files = {"program/blank.json": payload}
    if not args.without_presentation:
        descriptor = {"api_version": 1, "runtime": "html", "entry": "resources/index.html", "summary_entry": "resources/summary.html"}
        files["resources/presentation.json"] = json.dumps(descriptor, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        for field in ("entry", "summary_entry"):
            path = descriptor[field]
            resource = source.parent / "ui" / Path(path).name
            data = resource.read_bytes()
            if not 0 < len(data) <= 512 * 1024:
                parser.error("UI resources must be 1 byte to 512 KiB")
            data.decode("utf-8", errors="strict")
            files[path] = data
    manifest = {
        "format": "AIL_SELF_V1", "module_type": "self", "package_kind": "module",
        "package_schema_version": 1, "identity_id": identity, "module_version": args.version,
        "state_schema_version": 1, "compatible_state_schemas": [1],
        "integrity": {"algorithm": "sha256", "entries": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".ails.partial")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("self.json", json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
            for name, data in files.items():
                archive.writestr(name, data)
        temporary.replace(args.output)
    finally:
        temporary.unlink(missing_ok=True)
    print(json.dumps({"package": str(args.output), "identity_id": identity, "module_version": args.version,
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}, ensure_ascii=False))

if __name__ == "__main__":
    main()
