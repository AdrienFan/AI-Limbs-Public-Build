#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
APK = Path(os.environ.get("HUB_APK_PATH", str(ROOT / "plugin-lab/plugins/extension-hub/build/outputs/apk/debug/plugin-extension-hub-debug.apk")))
MANIFEST = ROOT / "plugin-lab/packages/extension-hub/plugin.json"
OUT_DIR = ROOT / "dist"
PAYLOAD_ENTRY = "payload/plugin.apk"
EXPECTED_APK_SHA256 = os.environ.get("HUB_EXPECTED_APK_SHA256", "").strip().lower()
SIGNER_ID = "ai-limbs-parent-plugin-dev-v1"
SIGNER_FINGERPRINT = "f9864bbfa24e7eb1324a47dc41c0fad57119a4d9ab3f516af891c6ab413c0aca"

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def main() -> None:
    if not APK.is_file():
        raise SystemExit(f"Missing Hub APK: {APK}")
    apk_bytes = APK.read_bytes()
    apk_sha = sha256_bytes(apk_bytes)
    if EXPECTED_APK_SHA256 and apk_sha != EXPECTED_APK_SHA256:
        raise SystemExit(f"Hub APK SHA256 mismatch: expected {EXPECTED_APK_SHA256}, got {apk_sha}")

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("plugin_id") != "plugin.system.extension_hub" or manifest.get("version") != "1.5.5":
        raise SystemExit("Unexpected Extension Hub manifest identity/version")

    manifest["integrity"] = {
        "algorithm": "SHA-256",
        "entries": {PAYLOAD_ENTRY: apk_sha},
    }
    manifest["signature"] = {
        "algorithm": "Ed25519",
        "signer_id": SIGNER_ID,
        "entry": "META-INF/AILIMBS.SIG",
    }
    canonical = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    private_pem = os.environ.get("AILIMBS_PARENT_PLUGIN_PRIVATE_PEM", "")
    if not private_pem:
        raise SystemExit("AILIMBS_PARENT_PLUGIN_PRIVATE_PEM is missing")

    OUT_DIR.mkdir(exist_ok=True)
    out = OUT_DIR / "AI-Limbs-Extension-Hub-v1.5.5.ailp"

    with tempfile.TemporaryDirectory(prefix="ail-hub-sign-") as raw:
        tmp = Path(raw)
        key = tmp / "private.pem"
        key.write_text(private_pem)
        key.chmod(0o600)
        pub = tmp / "public.der"
        subprocess.run(
            ["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER", "-out", str(pub)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        actual_fingerprint = sha256_bytes(pub.read_bytes())
        if actual_fingerprint != SIGNER_FINGERPRINT:
            raise SystemExit(f"Parent signer fingerprint mismatch: {actual_fingerprint}")

        source = tmp / "manifest"
        sig = tmp / "signature"
        source.write_bytes(canonical)
        subprocess.run(
            ["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(source), "-out", str(sig)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
             "-inkey", str(pub), "-in", str(source), "-sigfile", str(sig)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("plugin.json", canonical)
            archive.writestr(PAYLOAD_ENTRY, apk_bytes)
            archive.writestr("META-INF/AILIMBS.SIG", sig.read_bytes())

    digest = sha256_bytes(out.read_bytes())
    sha_file = out.with_suffix(out.suffix + ".sha256")
    sha_file.write_text(f"{digest}  {out.name}\n", encoding="utf-8")
    print(f"Hub APK sha256={apk_sha}")
    print(f"{out.name} sha256={digest}")

if __name__ == "__main__":
    main()
