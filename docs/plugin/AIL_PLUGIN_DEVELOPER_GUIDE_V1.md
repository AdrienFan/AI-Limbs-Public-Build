# AI Limbs Plugin Developer Guide v1

Status: Non-normative guide
Normative sources: `AIL_PLUGIN_SPEC_V1.md`, `AIL_PLUGIN_MANIFEST_V1.md`, `AIL_PLUGIN_EXTENSION_POINTS_V1.md`, and `plugin-manifest-v1.schema.json`.

If this guide conflicts with a normative source, the normative source wins.

## 1. Before writing code: answer 10 questions

1. What is the stable `plugin_id`?
2. What user-facing name should Plugin Center show?
3. What exact functional description should Plugin Center show?
4. What plugin version is being built?
5. Is activation `hot`, `restart_required`, or `cold_extension`?
6. What Runtime is used?
7. Which Extension Points does it implement?
8. Which Plugin Capabilities does it expose, if any?
9. Which permissions/scopes are requested?
10. Which plugin/service dependencies are mandatory?

Do not begin by reading random Kernel internals. First read the plugin specification, then only the subsystem Extension Point contract needed by the plugin.

## 2. Choose the runtime

For the 0.7.1 baseline, the first production Hot Plugin Runtime is `toolpkg`.

`declarative`, `wasm`, and `android_extension` are reserved roadmap runtimes and are not automatically usable merely because their names appear in the specification.
## 3. Create the package

Recommended ToolPkg package tree:

```text
hello.ailp
├── plugin.json
├── payload/
│   └── runtime.toolpkg
├── resources/
│   └── icon.png
└── signature/
    └── manifest.sig
```

The outer `.ailp` is the only installable artifact. Do not instruct users to install the inner `.toolpkg` separately.

For ToolPkg v1, keep outer identity and inner identity aligned:

```text
plugin.json plugin_id  == ToolPkg toolpkg_id
plugin.json version    == ToolPkg version
```

## 4. Write truthful Plugin Center metadata

`display.name` and `display.description` are required. The description should explain the actual user-visible function in one or two concise sentences.

Do not use filenames as names, generate descriptions from IDs, or rely on AI Limbs to invent missing metadata.

Example:

```json
"display": {
  "name": "TRIGGERcmd Bridge",
  "description": "Provides an independent TRIGGERcmd bridge path for invoking AI Limbs capabilities through the common policy and dispatcher pipeline."
}
```
## 5. Register only declared contributions

If the runtime will register a Plugin Capability, declare it first:

```json
"provides": {
  "capabilities": ["plugin.example.echo"]
}
```

Runtime registration must use the same declared ID. Plugin Capability IDs and aliases use `plugin.*` and execute through the shared Policy + Dispatcher path.

If the plugin is an implementation behind an existing subsystem, prefer a typed Extension Point instead of creating a new model tool.

Before using an Extension Point, read `AIL_PLUGIN_EXTENSION_POINTS_V1.md`. A reserved roadmap name is not an active API.

## 6. Use PluginContext, not host internals

External payload logic should depend only on the controlled PluginContext surface exposed by its Runtime Adapter.

Use `capabilityInvoker` to call AI Limbs capabilities. Do not search for a Dispatcher shortcut. Use sandbox data/cache APIs rather than arbitrary host filesystem paths. Use the secret broker rather than reading host credential files.

A request for access belongs in `permissions.requested_scopes`; approval is controlled by Kernel/user policy.

## 7. Verification before install

A development package is not ready merely because it can be zipped as `.ailp`.

Validate at least:

- archive structure and path safety;
- manifest JSON Schema;
- semantic API/runtime compatibility;
- runtime payload identity;
- dependencies and Extension Points;
- trust/signature policy;
- runtime-specific preflight.
## 8. Lifecycle acceptance checklist

Before calling a plugin implementation complete, verify the entire managed lifecycle:

```text
candidate .ailp
→ verifier says INSTALLABLE
→ install into Plugin Store
→ enable
→ mount ACTIVE
→ disable
→ all registrations/bindings disappear
→ re-enable
→ upgrade
→ rollback
→ uninstall
```

For ToolPkg Hot Plugins, additionally verify that deleting/moving the original external `.ailp` after install does not affect execution, because runtime must use the Plugin Store copy.

Verify that legacy ToolPkg install/scan paths cannot become a second lifecycle authority for managed `.ailp` payloads.

## 9. Failure tests are mandatory

At minimum test malformed ZIP, missing manifest, invalid JSON, missing `display.description`, unsupported API, unsafe `../` runtime entry, invalid ToolPkg identity/version, undeclared registration, unknown Extension Point, missing dependency, mount exception, mount timeout, stop exception, stop timeout, and revoked capability after disable.

A failed mount must leave no Registration Handle or Extension Binding residue.

## 10. Change discipline

When a plugin needs a Kernel feature not defined by the current ABI, do not code against private implementation details as a shortcut.

Instead:

1. define/update the normative plugin specification;
2. define the Extension Point/service/capability contract;
3. implement Kernel support;
4. add contract tests;
5. only then build the plugin against that public contract.

This keeps plugin development independent from reading the entire AI Limbs source tree.
