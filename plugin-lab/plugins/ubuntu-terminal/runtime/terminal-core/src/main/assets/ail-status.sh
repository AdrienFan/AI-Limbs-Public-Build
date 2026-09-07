#!/usr/bin/env bash
set -euo pipefail
repo=/root/laner/projects/AI-Limbs-Public-Build
cd "$repo"
echo '== AI Limbs main =='
git status --short --branch
git log -1 --oneline
echo
echo '== system environment runtime =='
echo 'Ubuntu terminal runtime is delivered by the plugin package; no root terminal submodule is expected.'
echo
echo '== GitHub =='
if gh auth status >/dev/null 2>&1; then echo 'GitHub CLI: authenticated'; else echo 'GitHub CLI: NOT authenticated'; fi
gh run list --repo AdrienFan/AI-Limbs-Public-Build --workflow android-build.yml --limit 5 2>/dev/null || true
