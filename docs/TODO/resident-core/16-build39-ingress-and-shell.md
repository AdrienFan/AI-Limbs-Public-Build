---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/resident-runtime-build26
baseline: 520072dfcac14fa936903ab85624adf2244d114d
---

# build39: Resident ingress ownership and process initialization

## Confirmed evidence

Installed build38 (code 111), Core session 039ffa5f-4d1c-428b-8238-eec18ce2e519: bootstrap.log shows Context, security, JNI and plugin storage preflight passed, with nine parent plugins present. Business activation failed in awaitResidentBridgeReady after 30 seconds. RDC and SentinelX were ONLINE; TriggerCMD was STOPPED. Every desired provider had start_requested=true.

The provider-local state caused the business-kernel shutdown before ordinary plugin services restored. This explains missing plugin UI and disconnected bridges without evidence of deleted plugin packages. shutdown.result.json confirms this failure. The subsequent socket-not-created exception occurred during server closure. The earlier QuickJS crash-buffer entry does not describe this activation; its JNI preflight passed.

Source inspection independently confirmed AndroidShellExecutor.setContext existed only in OperitApplication.onCreate. Standalone Core does not run that method. This is an initialization omission, not proof of every historical Shell crash.

## Implementation

- Validate schema-1 generic Bridge inventory, provider identities/counts, manager readiness and startup requests for every desired provider. Separate ingress_handoff_ready from provider-local connection states. Keep ready/fatal_error/phase unchanged as diagnostics. Missing bindings, malformed inventory or an ingress never handed to its provider remain explicit failures.
- Use awaitBusinessChildrenReady for all enabled children on registered points. Core ownership checks resident_subsystems_ready. Existing Ubuntu status fields remain diagnostic compatibility fields and no longer govern other subsystems.
- Initialize the shared AndroidShellExecutor Context during Core preflight, preserving the selected permission backend.
- Select ASK presentation by Core process identity, never by temporary proxy availability. Core does not construct its own permission overlay. ALLOW/ASK/FORBID semantics remain unchanged.
- Run Host component presentation on its main Looper. Permission overlays do not require a foreground Activity; Activity/window operations still validate their own prerequisites.
- Base versionCode 112 / build39. No provider-specific startup exception, credentials change, plugin disabling, alternate executor or automatic Host business restoration.

The lock-screen control objective, unique owner lease, authenticated IPC, connect-before-soTimeout ordering, canonical BUSINESS classloader, native preflight and policy handoff are preserved.

## Validation

Source review and git diff --check passed. No local Gradle build or tests were run. Submit the existing android-build.yml cloud workflow with assembleDebug.

The changed APK has not yet been installed. Real-device verification remains: Resident activation with multiple provider states, global search, Android Shell through configured bridges, registered subsystem capabilities, then repeat after lock-screen idle; ASK must still require its decision and FORBID must deny.

[DONE] Implementation and source review. Cloud submission is tracked by the resulting commit and workflow run.
