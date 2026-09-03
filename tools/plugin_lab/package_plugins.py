#!/usr/bin/env python3
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "dist"
DIST.mkdir(exist_ok=True)
PARENT_SIGNER_ID = "ai-limbs-parent-plugin-dev-v1"
PARENT_PUBLIC_FINGERPRINT = "f9864bbfa24e7eb1324a47dc41c0fad57119a4d9ab3f516af891c6ab413c0aca"
CHILD_SIGNER_ID = "ai-limbs-child-extension-dev-v1"
CHILD_PUBLIC_FINGERPRINT = "8b11ff0b92a3aa485c8aa755588f51e8e587bfa4bd120681aba9bc1da9c369ff"
SIGNATURE_ENTRY = "META-INF/AILIMBS.SIG"


def find_apk(rel: str) -> Path:
    files = sorted((ROOT / rel).glob("*.apk"))
    if len(files) != 1:
        raise SystemExit(f"Expected exactly one APK under {rel}, got {files}")
    return files[0]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_json_bytes(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def prepare_signing_key(temp_dir: Path, env_name: str, expected_fingerprint: str, stem: str) -> Path:
    source_value = os.environ.get(env_name, "").strip()
    if not source_value:
        raise SystemExit(f"{env_name} is required")
    source = Path(source_value).expanduser()
    if not source.is_file():
        raise SystemExit(f"Signing key file is missing: {source}")
    key_path = temp_dir / f"{stem}-private.pem"
    key_path.write_bytes(source.read_bytes())
    key_path.chmod(0o600)
    public_der = temp_dir / f"{stem}-public.der"
    subprocess.run(
        ["openssl", "pkey", "-in", str(key_path), "-pubout", "-outform", "DER", "-out", str(public_der)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    actual = sha256_file(public_der)
    if actual != expected_fingerprint:
        raise SystemExit(f"{stem} signing key fingerprint mismatch: {actual}")
    return key_path


def prepare_parent_key(temp_dir: Path) -> Path:
    return prepare_signing_key(
        temp_dir,
        "AILIMBS_PARENT_PLUGIN_PRIVATE_PEM_PATH",
        PARENT_PUBLIC_FINGERPRINT,
        "parent",
    )


def prepare_child_key(temp_dir: Path) -> Path:
    return prepare_signing_key(
        temp_dir,
        "AILIMBS_CHILD_EXTENSION_PRIVATE_PEM_PATH",
        CHILD_PUBLIC_FINGERPRINT,
        "child",
    )


def sign_bytes(data: bytes, key_path: Path, temp_dir: Path, stem: str) -> bytes:
    data_path = temp_dir / f"{stem}.manifest"
    sig_path = temp_dir / f"{stem}.sig"
    data_path.write_bytes(data)
    subprocess.run(
        ["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key_path), "-in", str(data_path), "-out", str(sig_path)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return sig_path.read_bytes()


def pack_parent(out_name: str, manifest_path: Path, apk: Path, apk_entry: str, key_path: Path, temp_dir: Path) -> Path:
    root = json.loads(manifest_path.read_text(encoding="utf-8"))
    root.pop("integrity", None)
    root.pop("signature", None)
    root["integrity"] = {
        "algorithm": "SHA-256",
        "entries": {apk_entry: sha256_file(apk)},
    }
    root["signature"] = {
        "algorithm": "Ed25519",
        "signer_id": PARENT_SIGNER_ID,
        "entry": SIGNATURE_ENTRY,
    }
    manifest_bytes = canonical_json_bytes(root)
    signature = sign_bytes(manifest_bytes, key_path, temp_dir, out_name.replace(".", "-"))
    out = DIST / out_name
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr("plugin.json", manifest_bytes)
        archive.write(apk, apk_entry)
        archive.writestr(SIGNATURE_ENTRY, signature)
    return out


def pack_child(
    out_name: str,
    manifest: Path,
    apk: Path,
    apk_entry: str,
    key_path: Path,
    temp_dir: Path,
) -> Path:
    root = json.loads(manifest.read_text(encoding="utf-8"))
    root.pop("integrity", None)
    root.pop("signature", None)
    root["integrity"] = {
        "algorithm": "SHA-256",
        "entries": {apk_entry: sha256_file(apk)},
    }
    root["signature"] = {
        "algorithm": "Ed25519",
        "signer_id": CHILD_SIGNER_ID,
        "entry": SIGNATURE_ENTRY,
    }
    manifest_bytes = canonical_json_bytes(root)
    signature = sign_bytes(manifest_bytes, key_path, temp_dir, out_name.replace(".", "-"))
    out = DIST / out_name
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr(manifest.name, manifest_bytes)
        archive.write(apk, apk_entry)
        archive.writestr(SIGNATURE_ENTRY, signature)
    return out


PARENTS = [
    ("plugin-extension-hub.ailp", ROOT / "plugin-lab/packages/extension-hub/plugin.json", find_apk("plugin-lab/plugins/extension-hub/build/outputs/apk/debug"), "payload/plugin.apk"),
    ("bridge-core.ailp", ROOT / "plugin-lab/packages/bridge-core/plugin.json", find_apk("plugin-lab/plugins/bridge-core/build/outputs/apk/debug"), "payload/plugin.apk"),
    ("developer-guide.ailp", ROOT / "plugin-lab/packages/developer-guide/plugin.json", find_apk("plugin-lab/plugins/developer-guide/build/outputs/apk/debug"), "payload/plugin.apk"),
    ("ai-limbs-packager.ailp", ROOT / "plugin-lab/packages/packager/plugin.json", find_apk("plugin-lab/plugins/packager/build/outputs/apk/debug"), "payload/plugin.apk"),
]
CHILDREN = [
    ("rdc-provider.ailx", ROOT / "plugin-lab/packages/rdc/extension.json", find_apk("plugin-lab/extensions/rdc/build/outputs/apk/debug"), "payload/extension.apk"),
    ("triggercmd-provider.ailx", ROOT / "plugin-lab/packages/triggercmd/extension.json", find_apk("plugin-lab/extensions/triggercmd/build/outputs/apk/debug"), "payload/extension.apk"),
]


def print_artifact(out: Path) -> None:
    print(f"{out.name}\t{out.stat().st_size}\t{sha256_file(out)}")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="ailimbs-parent-sign-") as raw_temp:
        temp_dir = Path(raw_temp)
        key_path = prepare_parent_key(temp_dir)
        for out_name, manifest, apk, entry in PARENTS:
            print_artifact(pack_parent(out_name, manifest, apk, entry, key_path, temp_dir))
    with tempfile.TemporaryDirectory(prefix="ailimbs-child-sign-") as raw_temp:
        temp_dir = Path(raw_temp)
        key_path = prepare_child_key(temp_dir)
        for out_name, manifest, apk, entry in CHILDREN:
            print_artifact(pack_child(out_name, manifest, apk, entry, key_path, temp_dir))


if __name__ == "__main__":
    main()
