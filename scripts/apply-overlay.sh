#!/usr/bin/env bash
set -euo pipefail
TARGET="${1:?usage: apply-overlay.sh <operit-baseline-worktree>}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED_BASE="04f873a6a86916f03a1bd6547846445e317cf55a"
actual="$(git -C "$TARGET" rev-parse HEAD)"
if [[ "$actual" != "$EXPECTED_BASE" ]]; then
  echo "Refusing to apply overlay: expected Operit $EXPECTED_BASE, got $actual" >&2
  exit 2
fi
rsync -a "$REPO_ROOT/overlay/current/" "$TARGET/"
if [[ -s "$REPO_ROOT/overlay/delete-list.txt" ]]; then
  while IFS= read -r path; do
    [[ -z "$path" ]] || rm -rf -- "$TARGET/$path"
  done < "$REPO_ROOT/overlay/delete-list.txt"
fi
echo "AI Limbs overlay applied to $TARGET"
