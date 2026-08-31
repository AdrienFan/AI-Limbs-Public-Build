# Plugin Lab architecture

## Stable base

The installed base owns only mechanisms that cannot safely be replaced at runtime:

- package parsing, path validation, trust decisions, and scope approval;
- versioned install, enable/disable, activation, rollback, and quarantine;
- sandboxed data/cache directories and secret brokering;
- capability and extension registries with owner-scoped revocation;
- a generic renderer for versioned declarative UI;
- startup restoration and runtime timeout enforcement.

The base does not contain the Runtime Log Viewer or any other optional tool.

## Plugin-owned behavior

A mounted plugin may contribute:

- headless capabilities under the `plugin.*` namespace;
- home tiles through `ai_limbs.ui.home_tile@1`;
- screens through `ai_limbs.ui.screen@1`;
- one hot-swappable application theme through `ai_limbs.ui.theme@1`.
  Theme v1 keeps `mode`/`pure_black` compatible and optionally accepts a Material color map plus `background_gradient`.

Disabling, upgrading, rolling back, or uninstalling the owner revokes all of those contributions
as one lifecycle transaction.

## Runtime boundary

`declarative@1` reads JSON only. It supports `echo`, `constant`, and scoped
`host_capability` operations plus text/button UI blocks. Packages containing APK, Class, Dex,
Jar, or native SO payloads are rejected. Plugin payloads never receive Android `Context`,
host paths, reflection, or class-loading APIs.

Unsigned packages can be approved only through the clearly labelled Plugin Lab development flow.
Requested scopes must exactly match the scopes approved at install time and are persisted per
immutable plugin version.

## Growth path

New behavior should be added by defining a versioned host capability or extension point, then
shipping the feature implementation as a plugin. General-purpose code plugins require a future
sandbox adapter such as WASM or an isolated process. They must keep the same registrar, scope,
timeout, and revocation contracts; in-process Dex/Jar execution is not an acceptable extension
boundary.

“Change the core” therefore means selecting or replacing routed policies and capabilities, not
allowing a package to patch arbitrary base classes. This keeps the base stable while preserving
upgradeable behavior.
