#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
src = root / "plugin-lab/extensions/ubuntu-system/src/main/java/com/ai/limbs/extensions/systemenvironment/ubuntu"
errors = []

capabilities = (src / "UbuntuSubsystemCapabilities.kt").read_text(encoding="utf-8")
limiter = (src / "UbuntuHiddenExecutorKeyLimiter.kt").read_text(encoding="utf-8")
process = (src / "UbuntuSubsystemProcessCapability.kt").read_text(encoding="utf-8")
filesystem = (src / "UbuntuSubsystemFileSystemCapability.kt").read_text(encoding="utf-8")
installer = (src / "UbuntuSubsystemNativeRuntimeInstaller.kt").read_text(encoding="utf-8")
build = (root / "plugin-lab/extensions/ubuntu-system/build.gradle.kts").read_text(encoding="utf-8")
manifest = (root / "plugin-lab/packages/ubuntu-system/extension.json").read_text(encoding="utf-8")

required = {
    "hidden executor limiter owned by child": ("UbuntuHiddenExecutorKeyLimiter.normalize", capabilities),
    "bounded hidden slots": ("SECONDARY_SLOT_COUNT = 3", limiter),
    "process generic alias": ('"plugin.system_environment.process"', process),
    "filesystem generic alias": ('"plugin.system_environment.filesystem"', filesystem),
    "host native runtime substrate": ("host.nativeRuntime", installer),
    "child excludes host busybox": ('"**/libbusybox.so"', build),
    "child excludes host proot": ('"**/liboperit_proot.so"', build),
}
for name, (token, text) in required.items():
    if token not in text:
        errors.append(name)

if "host.ubuntu.runtime@1" in manifest or "host.ubuntu.runtime@1" in capabilities:
    errors.append("Ubuntu child must not depend on retired host.ubuntu.runtime@1")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    sys.exit(1)

print("Ubuntu child ownership: PASS")
print("runtime/session/process/filesystem/limiter are child-owned; native executable substrate remains Host-owned")
