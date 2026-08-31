# Plugin Lab architecture

## Stable base

The installed base owns mechanisms that cannot safely be delegated to arbitrary plugins:

- package parsing, path validation, trust decisions, and scope approval;
- versioned install, enable/disable, activation, rollback, quarantine, and `BLOCKED` state;
- sandboxed data/cache directories and secret brokering;
- capability and extension registries with owner-scoped revocation;
- Host Surface Policy and administrator-gated control of exposed host interfaces;
- a generic renderer for versioned declarative UI;
- startup restoration and runtime timeout enforcement.

The base contains no Runtime Log Viewer, Echo tool, skin, or other optional feature package.

## Host Surface Policy

Kernel invariants are not switchable. Developer mode only controls surfaces intentionally exposed to
plugins: versioned extension points, host capabilities, and plugin capability/service/provider buses.
Surfaces default to allowed until an administrator explicitly stores a restriction, preserving
compatibility when a new host version introduces the policy layer.

A manifest-known blocked requirement prevents mount and records `enabled=true` with lifecycle
`BLOCKED`. If an active plugin becomes blocked, its mount scope is revoked immediately. Restoring the
surface automatically retries enabled plugins. Host capabilities that cannot yet be inferred from the
manifest are still denied at invocation time; future manifest revisions may make those dependencies
fully preflightable.

## Plugin-owned behavior

A mounted plugin may contribute headless `plugin.*` capabilities, home tiles through
`ai_limbs.ui.home_tile@1`, screens through `ai_limbs.ui.screen@1`, and one hot-swappable application
theme through `ai_limbs.ui.theme@1`. Theme v1 keeps `mode`/`pure_black` compatibility and optionally
accepts a Material color map plus `background_gradient`.

Disabling, blocking, upgrading, rolling back, or uninstalling an owner revokes its contributions as
one lifecycle transaction.

## Runtime boundary

`declarative@1` reads JSON only. It supports `echo`, `constant`, scoped `host_capability` operations,
and declarative UI blocks. APK, Class, Dex, Jar, and native SO payloads are rejected. Plugin payloads
do not receive Android `Context`, host paths, reflection, or class-loading APIs.

Unsigned packages can be explicitly approved only in the labelled Plugin Lab development flow.
Requested scopes must exactly match install-time approval and are persisted per immutable version.

## Administrator security

Destructive plugin management and developer controls use a separate administrator credential.
Plugin Lab stores no plaintext password or recovery key. A random Admin Master Key is wrapped
independently by password-derived and recovery-derived AES-GCM keys. A recovery key can replace a
forgotten password; regenerating recovery invalidates the previous recovery credential.

This v1 protects the Plugin Center management surface. It is not a substitute for Android device
lock security or protection against a fully compromised/rooted application process.

## Usage statistics and inactivity policy

Plugin usage is counted at real execution boundaries, not by opening a Plugin Center detail card.
Opening a plugin-owned screen counts once; a successful top-level `plugin.*` capability invocation
counts once. Nested host-capability calls do not add extra usage. Future service/provider routers
must record usage at their own top-level dispatch boundary.

Developer mode may configure automatic disabling of ordinary ACTIVE plugins after an inactivity
threshold. Production thresholds use whole days; Plugin Lab also exposes a 5-second minimum test
mode. System-role plugins and the currently bound global theme are exempt. The grace baseline is the
latest real use, explicit enable time, or policy-enable time, so process restart/mount restoration does
not reset inactivity age.

## Growth path

New behavior should be added by defining a versioned host capability or extension point, then
shipping the implementation as a plugin. General-purpose code plugins require a future sandbox
adapter such as WASM or an isolated process while preserving the same registrar, policy, timeout,
and revocation contracts. In-process Dex/Jar loading is not an acceptable extension boundary.
