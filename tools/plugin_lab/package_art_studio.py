#!/usr/bin/env python3
"""Build the .ailp from the exact committed Art Studio manifest and existing parent signer."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SIGNER_FINGERPRINT = "f9864bbfa24e7eb1324a47dc41c0fad57119a4d9ab3f516af891c6ab413c0aca"


def main():
    apks = list((ROOT / "plugin-lab/plugins/art-studio/build/outputs/apk/debug").glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit("Expected exactly one Art Studio APK")
    apk = apks[0]
    manifest = json.loads((ROOT / "plugin-lab/packages/art-studio/plugin.json").read_text(encoding="utf-8"))
    manifest["integrity"] = {"algorithm": "SHA-256", "entries": {
        "payload/plugin.apk": hashlib.sha256(apk.read_bytes()).hexdigest()}}
    manifest["signature"] = {"algorithm": "Ed25519", "signer_id": "ai-limbs-parent-plugin-dev-v1",
                             "entry": "META-INF/AILIMBS.SIG"}
    encoded = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    with tempfile.TemporaryDirectory(prefix="ail-art-studio-sign-") as temporary:
        work = Path(temporary)
        key = work / "private.pem"
        key.write_text(os.environ["AILIMBS_PARENT_PLUGIN_PRIVATE_PEM"])
        key.chmod(0o600)
        public = work / "public.der"
        subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER", "-out", str(public)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if hashlib.sha256(public.read_bytes()).hexdigest() != SIGNER_FINGERPRINT:
            raise SystemExit("Parent signer fingerprint mismatch")
        payload, signature = work / "manifest", work / "signature"
        payload.write_bytes(encoded)
        subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key),
                        "-in", str(payload), "-out", str(signature)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
                        "-inkey", str(public), "-in", str(payload), "-sigfile", str(signature)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        dist = ROOT / "dist"
        dist.mkdir(exist_ok=True)
        output = dist / f"AI-Limbs-Art-Studio-v{manifest['version']}.ailp"
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("plugin.json", encoded)
            package.write(apk, "payload/plugin.apk")
            package.writestr("META-INF/AILIMBS.SIG", signature.read_bytes())
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        output.with_suffix(".ailp.sha256").write_text(f"{digest}  {output.name}\n")
        print(f"{output.name} sha256={digest}")


if __name__ == "__main__":
    main()
