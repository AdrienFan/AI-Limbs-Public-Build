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
if "InProcessMetadataOnlyProvider" not in source:
    print("Laner Chat discovery provider must use the metadata-only Resident transport marker.", file=sys.stderr)
    sys.exit(1)
if re.search(r"registerProvider\(\s*LANER_CHAT_PROVIDER_ID,\s*controller", source):
    print("LanerChatController must stay Worker-local and must not cross the Provider transport.", file=sys.stderr)
    sys.exit(1)

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

controller = (SOURCE_ROOT / "LanerChatController.kt").read_text(encoding="utf-8")
contract = (SOURCE_ROOT / "LanerChatContract.kt").read_text(encoding="utf-8")
mode_provider = (SOURCE_ROOT / "LanerChatModeProvider.kt").read_text(encoding="utf-8")
service = (SOURCE_ROOT / "LanerChatBridgeService.kt").read_text(encoding="utf-8")

status_start = controller.find("fun status(): JSONObject {")
status_end = controller.find("\n    fun sessionOpen(", status_start)
if status_start < 0 or status_end < 0:
    print("Laner Chat status() contract is missing.", file=sys.stderr)
    sys.exit(1)
status_body = controller[status_start:status_end]
if "markAgentSeen" in status_body or "touchAgentLocked" in status_body:
    print("Laner Chat status() must be read-only and must not refresh Agent presence.", file=sys.stderr)
    sys.exit(1)
if "hasActiveTurn = mailbox.activeTurnId != null" not in status_body:
    print("Laner Chat status() must treat an active Assistant Turn as online.", file=sys.stderr)
    sys.exit(1)
if "hasActiveTurn" not in contract or "hasActiveTurn = hasActiveTurn" not in mode_provider:
    print("Laner Chat UI and presence contract must share active-turn presence semantics.", file=sys.stderr)
    sys.exit(1)
if "fun markAgentSeen(" in service:
    print("Laner Chat must not expose a generic markAgentSeen() hook to read paths.", file=sys.stderr)
    sys.exit(1)

def method_slice(name: str) -> str:
    start = service.find(f"fun {name}(")
    if start < 0:
        return ""
    end = service.find("\n    @Synchronized", start + 1)
    return service[start:] if end < 0 else service[start:end]

required_agent_touches = {
    "completeTurn": "touchAgentLocked(existing.sessionId)",
    "resolveTurnWithoutReply": "touchAgentLocked(existing.sessionId)",
}
for method_name, token in required_agent_touches.items():
    body = method_slice(method_name)
    if token not in body:
        print(
            f"Laner Chat Agent action {method_name} must refresh presence via {token}.",
            file=sys.stderr,
        )
        sys.exit(1)

for method_name in ("cancelActiveTurn", "resumeScheduler"):
    if "touchAgentLocked(" in method_slice(method_name):
        print(
            f"Laner Chat user-controllable action {method_name} must not synthesize Agent presence.",
            file=sys.stderr,
        )
        sys.exit(1)

mode_provider = (SOURCE_ROOT / "LanerChatModeProvider.kt").read_text(encoding="utf-8")
required_priority_presentation_tokens = [
    'uiHost.invokeHostCapability(',
    '"host.chat@1"',
    '"operation", "publish_user"',
    'LanerChatPriority.HIGH -> "danger"',
    'LanerChatPriority.NORMAL -> "info"',
    'LanerChatPriority.LOW -> "success"',
    '.put("host_message_published", hostMessagePublished)',
    '"presentation_json"',
    '"presentation"',
    '"semantic_tone"',
    '"operation", "set_presentation"',
    '"message_presentations"',
    'syncHistoricalPresentations(',
]
missing_priority_presentation = [
    token for token in required_priority_presentation_tokens if token not in mode_provider
]
if missing_priority_presentation:
    print(
        "Laner Chat priority presentation contract is incomplete: "
        + ", ".join(missing_priority_presentation),
        file=sys.stderr,
    )
    sys.exit(1)

presentation_snapshot_start = service.find("fun presentationSnapshot(")
presentation_snapshot_end = service.find("\n    /**", presentation_snapshot_start + 1)
if presentation_snapshot_start < 0 or presentation_snapshot_end < 0:
    print("Laner Chat presentationSnapshot() contract is missing.", file=sys.stderr)
    sys.exit(1)
presentation_snapshot_body = service[presentation_snapshot_start:presentation_snapshot_end]
if "touchAgentLocked(" in presentation_snapshot_body:
    print("Laner Chat presentationSnapshot must remain read-only.", file=sys.stderr)
    sys.exit(1)

entry = (SOURCE_ROOT / "LanerChatEntry.kt").read_text(encoding="utf-8")
service = (SOURCE_ROOT / "LanerChatBridgeService.kt").read_text(encoding="utf-8")
required_attention_tokens = [
    '"host.attention@1"',
    'service.attentionSummary()',
    'addAttentionGroup(groups, "unread", "未读", summary.unread)',
    'addAttentionGroup(groups, "pending", "未处理", summary.pending)',
    'attentionItem("urgent", "紧急", counts.high, "danger")',
    'attentionItem("normal", "普通", counts.normal, "info")',
    'attentionItem("later", "稍后", counts.low, "success")',
    'if (counts.high > 0)',
    'if (counts.normal > 0)',
    'if (counts.low > 0)',
]
missing_attention = [token for token in required_attention_tokens if token not in entry]
if missing_attention:
    print(
        "Laner Chat Attention publication contract is incomplete: "
        + ", ".join(missing_attention),
        file=sys.stderr,
    )
    sys.exit(1)
if "LanerChatMessageStatus.PENDING" not in service or "LanerChatMessageStatus.DELIVERED" not in service:
    print("Laner Chat must derive unread/pending Attention from durable message state.", file=sys.stderr)
    sys.exit(1)

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

if service.count("getSharedPreferences") > 1:
    print("Laner Chat runtime may read legacy SharedPreferences only once for migration.", file=sys.stderr)
    sys.exit(1)
if "laner_chat_mailbox_v1.json" not in service or "stateFile" not in service:
    print("Laner Chat runtime must persist in plugin-owned dataDir state.", file=sys.stderr)
    sys.exit(1)
if "LEGACY_PREFERENCES_NAME" in service and "getSharedPreferences" not in service:
    print("Legacy migration declaration is incomplete.", file=sys.stderr)
    sys.exit(1)

print(
    f"Laner Chat plugin contract OK: {len(runtime)} capabilities; "
    "base imports absent; plugin-owned persistence and legacy migration verified."
)
