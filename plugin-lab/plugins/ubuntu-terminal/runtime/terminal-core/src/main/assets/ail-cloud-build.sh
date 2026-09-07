#!/usr/bin/env bash
set -euo pipefail
repo=/root/laner/projects/AI-Limbs-Public-Build
cd "$repo"
task=${1:-assembleDebug}
ref=${2:-$(git branch --show-current)}
case "$task" in
  assembleDebug|:app:assembleNightly|:app:assembleClone|:app:bundleRelease) ;;
  *) echo "Unsupported task: $task" >&2; exit 2 ;;
esac
if [ -z "$ref" ]; then echo 'Current branch is detached; pass a ref explicitly.' >&2; exit 2; fi
echo "Dispatching android-build.yml: ref=$ref task=$task"
gh workflow run android-build.yml --repo AdrienFan/AI-Limbs-Public-Build --ref "$ref" -f gradle_task="$task"
sleep 2
gh run list --repo AdrienFan/AI-Limbs-Public-Build --workflow android-build.yml --branch "$ref" --limit 3
