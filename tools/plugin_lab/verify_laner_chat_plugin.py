#!/usr/bin/env python3
"""Static contract checks for the extracted Laner Chat shadow plugin."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "plugin-lab/packages/laner-chat/plugin.json"
ENTRY = ROOT / "plugin-lab/plugins/laner-chat/src/main/java/com/ai/limbs/plugins/lanerchat/LanerChatEntry.kt"
SOURCE_ROOT = ROOT / "plugin-lab/plugins/laner-chat/src/main/java/com/ai/limbs/plugins/lanerchat"

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
plugin_id = manifest["plugin_id"]
declared_list = manifest["provides"]["capabilities"]
declared = set(declared_list)

if len(declared) != len(declared_list):
    print("Laner Chat manifest has duplicate capability declarations.", file=sys.stderr)
    sys.exit(1)

source = ENTRY.read_text(encoding="utf-8")
runtime_names = set(re.findall(r'\bcapability\(\s*"([^"]+)"', source))
runtime = {f"{plugin_id}.{name}" for name in runtime_names}

missing = sorted(runtime - declared)
extra = sorted(declared - runtime)
if missing or extra:
    if missing:
        print("Runtime capabilities missing from plugin.json:", file=sys.stderr)
        for item in missing:
            print(f"  - {item}", file=sys.stderr)
    if extra:
        print("Manifest capabilities not registered by runtime:", file=sys.stderr)
        for item in extra:
            print(f"  - {item}", file=sys.stderr)
    sys.exit(1)

for kotlin_file in SOURCE_ROOT.glob("*.kt"):
    text = kotlin_file.read_text(encoding="utf-8")
    if "com.ai.assistance.operit" in text:
        print(
            f"Extracted Laner Chat still imports base implementation: {kotlin_file.name}",
            file=sys.stderr,
        )
        sys.exit(1)

service = (SOURCE_ROOT / "LanerChatBridgeService.kt").read_text(encoding="utf-8")
required_core_tokens = [
    "fun openSession(",
    "fun enqueueMailbox(",
    "fun waitForNotification(",
    "fun fetchInbox(",
    "fun claimTurn(",
    "fun completeTurn(",
    "fun resolveTurnWithoutReply(",
    "fun cancelActiveTurn(",
    "fun resumeScheduler(",
    "fun reply(",
    "fun prepareProactiveMessage(",
]
missing_tokens = [token for token in required_core_tokens if token not in service]
if missing_tokens:
    print("Laner Chat extracted core lost required operations:", file=sys.stderr)
    for token in missing_tokens:
        print(f"  - {token}", file=sys.stderr)
    sys.exit(1)

if "PREFERENCES_NAME" in service or "getSharedPreferences" in service:
    print("Shadow plugin must use plugin-owned dataDir, not base SharedPreferences.", file=sys.stderr)
    sys.exit(1)

print(
    f"Laner Chat plugin contract OK: {len(runtime)} capabilities; "
    "base imports absent; core mailbox/turn operations preserved."
)
