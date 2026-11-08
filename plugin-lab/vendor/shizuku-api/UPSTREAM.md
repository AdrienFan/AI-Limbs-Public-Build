# Upstream source

Source: https://github.com/RikkaApps/Shizuku-API
Revision: a27f6e4151ba7b39965ca47edb2bf0aeed7102e5
API version: 13.1.5
License: Apache-2.0, retained in LICENSE.

AI Limbs changes: Gradle module names and dependency wiring; Rish Java types retained for upstream Service linkage, native Rish build omitted. The internal service rejects Rish and external user-service calls. It supports the existing Shizuku Binder protocol for Host shell/system operations only.

Service.java additionally redacts command/env values from process logs and restores Binder identity in a finally block around forwarded transactions.

## AI Limbs external clients (v0.2.0 source iteration)

The server-shared Service now invokes a protected no-op `onRemoteProcessCreated(uid, process)` hook after process creation. PermissionServer uses it to associate child processes with a verified client UID and release them on revocation. Other consumers retain the default behavior. UserServiceManager removes destroyed records from its package index and iterates a snapshot when removing a package's services.

External authorization, Provider handoff, BinderContainer wire compatibility and UserService bootstrap are implemented in the AI Limbs permission-server module; no app authorization decisions were moved into the Android base.

Per-client UserService startup exceptions now remove only the failed record instead of escaping the executor and terminating the whole daemon. Death-recipient cleanup handles an already-removed registration explicitly.
