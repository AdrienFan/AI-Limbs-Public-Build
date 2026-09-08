#!/usr/bin/env bash
set -euo pipefail
repo=/root/laner/projects/AI-Limbs-Public-Build
cd "$repo"
echo 'Checking whitespace/errors...'
git diff --check
echo 'Checking system environment runtime ownership...'
echo 'Ubuntu terminal runtime is plugin-owned; no root terminal submodule is expected.'
echo 'Checking worktree...'
git status --short --branch
echo 'Preflight OK'
