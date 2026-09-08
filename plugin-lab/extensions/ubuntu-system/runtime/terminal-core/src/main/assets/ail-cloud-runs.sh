#!/usr/bin/env bash
set -euo pipefail
gh run list --repo AdrienFan/AI-Limbs-Public-Build --workflow android-build.yml --limit "${1:-10}"
