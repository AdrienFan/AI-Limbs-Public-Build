#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
app = root / "app/src/main/java"
errors = []

forbidden_tokens = (
    "plugin.ubuntu.",
    "host.ubuntu.runtime@1",
    "create_terminal_session",
    "execute_in_terminal_session",
    "execute_in_terminal_session_streaming",
    "execute_hidden_terminal_command",
    "close_terminal_session",
    "input_in_terminal_session",
    "get_terminal_session_screen",
    "resident_ubuntu_",
    "RESIDENT_UBUNTU_",
)
for path in app.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for token in forbidden_tokens:
        if token in text:
            errors.append(f"{path.relative_to(root)} contains forbidden Base Ubuntu/terminal token: {token}")

for rel in (
    "app/src/main/java/com/ai/assistance/operit/core/systemenvironment/SystemEnvironmentClient.kt",
    "app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardSystemEnvironmentCommandExecutor.kt",
    "app/src/main/java/com/ai/assistance/operit/plugins/center/UbuntuHiddenExecutorKeyLimiter.kt",
):
    if (root / rel).exists():
        errors.append(f"legacy Base compatibility file still exists: {rel}")

settings_path = root / "settings.gradle.kts"
if settings_path.exists():
    settings = settings_path.read_text(encoding="utf-8")
else:
    import subprocess
    settings = subprocess.check_output(
        ["git", "-C", str(root), "show", "HEAD:settings.gradle.kts"],
        text=True,
    )
if 'include(":terminal")' in settings:
    errors.append("legacy :terminal module is still included")

filesystem = (
    root
    / "app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/SystemEnvironmentFileSystemProvider.kt"
).read_text(encoding="utf-8")
if '"plugin.system_environment.filesystem"' not in filesystem:
    errors.append("Base filesystem bridge must use generic plugin.system_environment.filesystem alias")

process_router = (
    root / "app/src/main/java/com/ai/assistance/operit/integrations/ailimbs/AiLimbsProcessRouter.kt"
).read_text(encoding="utf-8")
if '"plugin.system_environment.process"' not in process_router:
    errors.append("Base process router must use generic plugin.system_environment.process alias")

build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
for native in ("libbusybox.so", "liboperit_proot.so", "libbash.so", "libsudo.so"):
    if f"src/hostNativeRuntime/jniLibs/arm64-v8a/{native}" not in build:
        errors.append(f"Host-owned native executable substrate missing from Base build: {native}")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    sys.exit(1)

print("System Environment child ownership: PASS")
print("Base -> generic plugin.system_environment.* aliases; Ubuntu runtime/business remains child-owned")
