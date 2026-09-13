#!/usr/bin/env python3
"""Package the permission service with the existing trusted parent-plugin signer."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FINGERPRINT = "f9864bbfa24e7eb1324a47dc41c0fad57119a4d9ab3f516af891c6ab413c0aca"

def main():
    apks = list((ROOT / "plugin-lab/plugins/permission-service/build/outputs/apk/debug").glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit("Expected exactly one permission service APK")
    apk = apks[0]
    with zipfile.ZipFile(apk) as payload:
        if "assets/permission-server.apk" not in payload.namelist():
            raise SystemExit("Permission server payload is missing")
        if "lib/arm64-v8a/libail_adb.so" not in payload.namelist():
            raise SystemExit("ADB pairing native library is missing")
    manifest = json.loads((ROOT / "plugin-lab/packages/permission-service/plugin.json").read_text())
    manifest["integrity"] = {"algorithm": "SHA-256", "entries": {
        "payload/plugin.apk": hashlib.sha256(apk.read_bytes()).hexdigest()}}
    manifest["signature"] = {"algorithm": "Ed25519", "signer_id": "ai-limbs-parent-plugin-dev-v1",
                             "entry": "META-INF/AILIMBS.SIG"}
    encoded = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    with tempfile.TemporaryDirectory(prefix="ail-permission-sign-") as temp:
        temp = Path(temp)
        key = temp / "private.pem"
        key.write_text(os.environ["AILIMBS_PARENT_PLUGIN_PRIVATE_PEM"])
        key.chmod(0o600)
        public = temp / "public.der"
        subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER", "-out", str(public)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if hashlib.sha256(public.read_bytes()).hexdigest() != FINGERPRINT:
            raise SystemExit("Parent signer fingerprint mismatch")
        data, signature = temp / "manifest", temp / "signature"
        data.write_bytes(encoded)
        subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key),
                        "-in", str(data), "-out", str(signature)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
                        "-inkey", str(public), "-in", str(data), "-sigfile", str(signature)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        dist = ROOT / "dist"
        dist.mkdir(exist_ok=True)
        out = dist / "AI-Limbs-Permission-Service-v0.1.0.ailp"
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("plugin.json", encoded)
            package.write(apk, "payload/plugin.apk")
            package.writestr("META-INF/AILIMBS.SIG", signature.read_bytes())
        digest = hashlib.sha256(out.read_bytes()).hexdigest()
        out.with_suffix(out.suffix + ".sha256").write_text(digest + "  " + out.name + "\n")
        print(out.name + " sha256=" + digest)

if __name__ == "__main__":
    main()
