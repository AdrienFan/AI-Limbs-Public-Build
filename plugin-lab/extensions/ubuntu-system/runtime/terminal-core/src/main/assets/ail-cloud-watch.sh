#!/usr/bin/env bash
set -euo pipefail
run_id=${1:-}
if [ -z "$run_id" ]; then
  run_id=$(gh run list --repo AdrienFan/AI-Limbs-Public-Build --workflow android-build.yml --limit 1 --json databaseId --jq '.[0].databaseId')
fi
[ -n "$run_id" ] || { echo 'No workflow run found.' >&2; exit 2; }
gh run watch "$run_id" --repo AdrienFan/AI-Limbs-Public-Build
