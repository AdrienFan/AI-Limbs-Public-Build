#!/usr/bin/env python3
"""Sign the privacy audit plugin using the existing parent-plugin release identity."""
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
    apks = list((ROOT / "plugin-lab/plugins/privacy-audit/build/outputs/apk/debug").glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit("Expected exactly one privacy audit payload APK")
    apk = apks[0]
    manifest = json.loads((ROOT / "plugin-lab/packages/privacy-audit/plugin.json").read_text())
    with zipfile.ZipFile(apk) as payload:
        if "classes.dex" not in payload.namelist():
            raise SystemExit("Payload has no executable dex")
        embedded = json.loads(payload.read("assets/plugin.json"))
        if embedded != manifest:
            raise SystemExit("Embedded manifest differs from package source")
    manifest["integrity"] = {"algorithm": "SHA-256", "entries": {
        "payload/plugin.apk": hashlib.sha256(apk.read_bytes()).hexdigest()}}
    manifest["signature"] = {"algorithm": "Ed25519", "signer_id": "ai-limbs-parent-plugin-dev-v1",
                             "entry": "META-INF/AILIMBS.SIG"}
    encoded = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    with tempfile.TemporaryDirectory(prefix="ail-privacy-sign-") as tmp:
        tmp = Path(tmp)
        key = tmp / "private.pem"
        key.touch(mode=0o600)
        key.write_text(os.environ["AILIMBS_PARENT_PLUGIN_PRIVATE_PEM"])
        public = tmp / "public.der"
        subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER", "-out", str(public)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if hashlib.sha256(public.read_bytes()).hexdigest() != FINGERPRINT:
            raise SystemExit("Existing parent signer fingerprint mismatch")
        data, signature = tmp / "manifest", tmp / "signature"
        data.write_bytes(encoded)
        subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key),
                        "-in", str(data), "-out", str(signature)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
                        "-inkey", str(public), "-in", str(data), "-sigfile", str(signature)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        dist = ROOT / "dist"
        dist.mkdir(exist_ok=True)
        out = dist / ("AI-Limbs-Privacy-Audit-v" + manifest["version"] + ".ailp")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("plugin.json", encoded)
            package.write(apk, "payload/plugin.apk")
            package.writestr("META-INF/AILIMBS.SIG", signature.read_bytes())
        with zipfile.ZipFile(out) as package:
            assert set(package.namelist()) == {"plugin.json", "payload/plugin.apk", "META-INF/AILIMBS.SIG"}
            assert hashlib.sha256(package.read("payload/plugin.apk")).hexdigest() == manifest["integrity"]["entries"]["payload/plugin.apk"]
        digest = hashlib.sha256(out.read_bytes()).hexdigest()
        out.with_suffix(out.suffix + ".sha256").write_text(digest + "  " + out.name + "\n")
        print(out.name + " sha256=" + digest)


if __name__ == "__main__":
    main()
