# AI Limbs

AI Limbs is an Android AI-agent project with direct device, terminal and remote-bridge capabilities.

## Source model

`main` contains the complete buildable AI Limbs source tree. The project no longer assembles itself from an external Operit baseline plus an overlay. Git commits and tags are the source/version anchors.

Current development build: **v0.5-build1**.

## Build

`.github/workflows/android-build.yml` checks out this repository directly, initializes the required `terminal` submodule, prepares Android/native/web dependencies, and builds the requested Android artifact.

## History and licensing

AI Limbs originated from Operit and retains applicable upstream copyright and LGPL-3.0 licensing obligations. `LICENSE` and `UPSTREAM-LICENSE.txt` preserve the relevant license text; `history/` keeps early migration patches for recovery/reference only.
