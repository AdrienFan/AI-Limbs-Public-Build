# AI Limbs

Private development repository for AI Limbs.

This repository contains only the private AI Limbs overlay, version history and build orchestration. The large Operit codebase remains an external public upstream base pinned by `BASELINE.json`.

## Repository model

- `overlay/current/` — current AI Limbs source overlay applied on top of the pinned Operit baseline.
- `history/` — incremental historical patches for v0.3, v0.4 and later versions.
- `scripts/apply-overlay.sh` — applies the private overlay to an already checked-out Operit baseline.
- `.github/workflows/android-build.yml` — private build workflow; checks out the public baseline, applies this private overlay, then runs the Android build.
- `stable` — last device-proven fallback line.
- `main` — current development/release-candidate line.

## Privacy rule

AI Limbs-specific code must be committed here, not to public Operit branches. Public Operit is used only as the pinned upstream base.

## Upstream notice

Operit is licensed under LGPL-3.0. `UPSTREAM-LICENSE.txt` preserves the upstream license text used by this project.
