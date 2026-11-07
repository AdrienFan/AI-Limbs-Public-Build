#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
src="$root/plugin-lab/plugins/privacy-audit/src"
javac -encoding UTF-8 -d "$tmp" "$src/main/java/com/ai/limbs/plugins/privacyaudit/AuditCore.java" "$src/main/java/com/ai/limbs/plugins/privacyaudit/ShellCommands.java" "$src/test/java/com/ai/limbs/plugins/privacyaudit/AuditCoreChecks.java"
java -cp "$tmp" com.ai.limbs.plugins.privacyaudit.AuditCoreChecks
mkdir -p "$tmp/bin"
cat > "$tmp/bin/dumpsys" <<'SH'
#!/bin/sh
cat "$PRIVACY_FIXTURE"
SH
chmod +x "$tmp/bin/dumpsys"
export PRIVACY_FIXTURE="$root/tools/plugin_lab/privacy_netstats_fixture.txt"
net_command=$(java -cp "$tmp" com.ai.limbs.plugins.privacyaudit.AuditCoreChecks --network-command)
PATH="$tmp/bin:$PATH" bash -c "set -o pipefail; $net_command" > "$tmp/traffic"
java -cp "$tmp" com.ai.limbs.plugins.privacyaudit.AuditCoreChecks --traffic-file "$tmp/traffic"
printf 'Permission Denial\n' > "$tmp/denied"
if PRIVACY_FIXTURE="$tmp/denied" PATH="$tmp/bin:$PATH" bash -c "set -o pipefail; $net_command" > "$tmp/invalid"; then
    echo 'FAIL: denied network data was accepted' >&2
    exit 1
fi
printf 'PASS: rejected missing or denied UID statistics\n'
