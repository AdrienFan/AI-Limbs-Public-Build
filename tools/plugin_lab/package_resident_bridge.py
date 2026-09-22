#!/usr/bin/env python3
"""Sign only the Resident Bridge fixes with the existing parent/child signer identities."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TARGETS = (
    ("bridge-core", "plugins/bridge-core", "plugin.json", "payload/plugin.apk",
     "AILIMBS_PARENT_PLUGIN_PRIVATE_PEM", "ai-limbs-parent-plugin-dev-v1",
     "f9864bbfa24e7eb1324a47dc41c0fad57119a4d9ab3f516af891c6ab413c0aca", "Bridge", ".ailp"),
    ("rdc", "extensions/rdc", "extension.json", "payload/extension.apk",
     "AILIMBS_CHILD_EXTENSION_PRIVATE_PEM", "ai-limbs-child-extension-dev-v1",
     "8b11ff0b92a3aa485c8aa755588f51e8e587bfa4bd120681aba9bc1da9c369ff", "RDC", ".ailx"),
    ("triggercmd", "extensions/triggercmd", "extension.json", "payload/extension.apk",
     "AILIMBS_CHILD_EXTENSION_PRIVATE_PEM", "ai-limbs-child-extension-dev-v1",
     "8b11ff0b92a3aa485c8aa755588f51e8e587bfa4bd120681aba9bc1da9c369ff", "TriggerCMD", ".ailx"),
    ("sentinelx", "extensions/sentinelx", "extension.json", "payload/extension.apk",
     "AILIMBS_CHILD_EXTENSION_PRIVATE_PEM", "ai-limbs-child-extension-dev-v1",
     "8b11ff0b92a3aa485c8aa755588f51e8e587bfa4bd120681aba9bc1da9c369ff", "SentinelX", ".ailx"),
)

def main():
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    for package, module, manifest_name, payload, env_key, signer, fingerprint, label, suffix in TARGETS:
        apks = list((ROOT / "plugin-lab" / module / "build/outputs/apk/debug").glob("*.apk"))
        if len(apks) != 1:
            raise SystemExit(f"Expected exactly one APK for {package}")
        apk = apks[0]
        manifest = json.loads((ROOT / "plugin-lab/packages" / package / manifest_name).read_text())
        manifest["integrity"] = {"algorithm": "SHA-256", "entries": {
            payload: hashlib.sha256(apk.read_bytes()).hexdigest()}}
        manifest["signature"] = {"algorithm": "Ed25519", "signer_id": signer,
                                 "entry": "META-INF/AILIMBS.SIG"}
        data = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        with tempfile.TemporaryDirectory(prefix="ail-resident-sign-") as raw:
            tmp = Path(raw)
            key = tmp / "private.pem"
            key.write_text(os.environ[env_key])
            key.chmod(0o600)
            pub = tmp / "public.der"
            subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER",
                            "-out", str(pub)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if hashlib.sha256(pub.read_bytes()).hexdigest() != fingerprint:
                raise SystemExit(f"Signer fingerprint mismatch for {package}")
            source, sig = tmp / "manifest", tmp / "signature"
            source.write_bytes(data)
            subprocess.run(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key),
                            "-in", str(source), "-out", str(sig)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER",
                            "-inkey", str(pub), "-in", str(source), "-sigfile", str(sig)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            out = dist / f"AI-Limbs-{label}-v{manifest['version']}{suffix}"
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(manifest_name, data)
                archive.write(apk, payload)
                archive.writestr("META-INF/AILIMBS.SIG", sig.read_bytes())
            digest = hashlib.sha256(out.read_bytes()).hexdigest()
            out.with_suffix(out.suffix + ".sha256").write_text(digest + "  " + out.name + "\n")
            print(out.name + " sha256=" + digest)

if __name__ == "__main__":
    main()
