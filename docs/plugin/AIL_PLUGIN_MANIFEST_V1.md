# AI Limbs Plugin Manifest v1

Status: Normative
Manifest path: `/plugin.json`
Format: `AIL_PLUGIN_V1`
Schema version: `1`

This document defines the v1 `plugin.json` contract. Structural validation is additionally expressed by `plugin-manifest-v1.schema.json`. Semantic validation remains a Kernel responsibility.

## 1. Minimal valid ToolPkg plugin

```json
{
  "format": "AIL_PLUGIN_V1",
  "schema_version": 1,
  "plugin_id": "com.example.hello",
  "version": "1.0.0",
  "api": { "min": 1, "target": 1 },
  "display": {
    "name": "Hello Plugin",
    "description": "Provides a simple Hello capability for AI Limbs plugin verification."
  },
  "roles": [],
  "activation": { "mode": "hot" },
  "runtime": {
    "kind": "toolpkg",
    "entry": "payload/runtime.toolpkg"
  }
}
```
## 2. Required root fields

| Field | Type | Required | Rule |
| --- | --- | --- | --- |
| `format` | string | YES | exactly `AIL_PLUGIN_V1` |
| `schema_version` | integer | YES | exactly `1` |
| `plugin_id` | string | YES | stable machine identity |
| `version` | string | YES | Semantic Version |
| `api` | object | YES | Plugin API compatibility |
| `display` | object | YES | user-facing metadata |
| `activation` | object | YES | lifecycle activation mode |
| `runtime` | object | YES | runtime contract |

Optional root fields are `roles`, `dependencies`, `permissions`, `provides`, `ui`, and `signature`.

Unknown fields MAY be rejected by the schema in strict mode. Any future required semantic field requires a schema/spec revision rather than silent reinterpretation.

## 3. `plugin_id`

Pattern:

```text
^[a-z0-9]+(?:[._-][a-z0-9]+)*$
```

Recommended third-party form is reverse-domain style such as `com.example.hello`. The `ai_limbs.*` namespace is reserved to first-party identities approved by Kernel trust policy.

Changing the display name MUST NOT change `plugin_id`. Upgrades and rollback are keyed by `plugin_id` plus `version`.
## 4. `version` and `api`

`version` MUST be SemVer such as `1.0.0` or `1.2.0-beta.1`.

`api` contains:

```json
{ "min": 1, "target": 1 }
```

`min` is the minimum Plugin API required. `target` is the API against which the plugin was authored/tested. In v1 both must be positive, `target >= min`, and the Kernel must support the declared range.

Plugin package version and Plugin API version are independent. A plugin may be version `8.4.2` while still targeting Plugin API `1`.

## 5. `display`

`display.name` and `display.description` are REQUIRED.

```json
{
  "display": {
    "name": "Hello Plugin",
    "description": "Provides a simple Hello capability for AI Limbs plugin verification.",
    "icon": "resources/icon.png"
  }
}
```

`name` is the Plugin Center display name. `description` is the publisher-provided user-facing functional description. AI Limbs MUST NOT fabricate either value.

`icon` is optional and must be a safe relative package entry. Future localization requires an explicit schema revision/extension; v1 strings are direct display strings.
## 6. `activation`

Allowed v1 modes:

- `hot` — mount/unmount without host APK reinstall.
- `restart_required` — activation requires AI Limbs restart/rebind.
- `cold_extension` — Android extension installation/system binding is required.

Runtime/activation compatibility is a semantic rule. For example, v1 `toolpkg` packages normally require `hot`; `android_extension` requires `cold_extension`.

## 7. `runtime`

```json
{
  "runtime": {
    "kind": "toolpkg",
    "entry": "payload/runtime.toolpkg",
    "config": {}
  }
}
```

`kind` is a symbolic runtime identifier. `entry` is required except for Kernel-approved `none` metadata/internal cases. `entry` MUST be a safe relative path contained by the installed version directory.

`config` is optional runtime-specific JSON. Runtime-specific schemas MAY further validate it before mount.

For ToolPkg v1, semantic verification MUST validate that the entry is a legal ToolPkg, its internal `toolpkg_id` equals outer `plugin_id`, and its internal version equals outer plugin `version`.

## 8. `roles`

`roles` is an optional array of symbolic descriptive roles. Roles do not grant trust, permission, System Plugin classification, or Android privileges.
## 9. `dependencies`

```json
{
  "dependencies": {
    "plugins": [
      { "id": "ai_limbs.example.core", "min_version": "1.0.0" }
    ],
    "services": [
      { "id": "ai_limbs.example.service", "min_api": 1 }
    ]
  }
}
```

Plugin dependency IDs use normal plugin ID rules. `min_version` is optional SemVer. Service IDs are symbolic and `min_api`, when present, must be positive.

Missing mandatory dependencies prevent enable/mount. Future richer version ranges require a schema revision; v1 formally supports minimum version/API semantics.

## 10. `permissions`

```json
{
  "permissions": {
    "requested_scopes": ["network.example"]
  }
}
```

Requested scopes are requests only. They do not grant permission, secret access, Android permission, Shell access, or Policy bypass.

A plugin MUST tolerate denied optional permissions or fail with an explicit permission/dependency error when access is mandatory.
## 11. `provides`

```json
{
  "provides": {
    "capabilities": ["plugin.example.echo"],
    "services": [],
    "providers": [],
    "extensions": [
      { "point": "ai_limbs.test.provider", "id": "example", "api": 1 }
    ]
  }
}
```

This object declares the maximum runtime contribution surface. Runtime code may register a subset but MUST NOT register undeclared identifiers.

`capabilities` contains model-callable Plugin Capability IDs. Canonical IDs and executable aliases must live in `plugin.*`.

`services` and legacy `providers` are symbolic contribution declarations. New subsystem routing SHOULD use typed `extensions` instead of inventing an untyped provider string.

Each extension requires `point`, `id`, and positive integer `api`. The pair `(point,id)` must be unique within a manifest. Kernel must already define the point and a compatible API before binding.

## 12. `signature`

```json
{
  "signature": {
    "algorithm": "...",
    "signer_id": "...",
    "entry": "signature/manifest.sig"
  }
}
```

The entry is a safe relative package path. Supported algorithms and signer trust policy are Kernel-defined. Presence of this object does not by itself make the package trusted.
## 13. `ui`

`ui` is optional runtime-specific/declarative UI metadata. In v1 it is not a permission surface and MUST NOT be interpreted as authorization to register Android Activities, Services, Receivers, Providers, or manifest permissions.

Plugin functional UI may be contributed only through Kernel-supported UI contracts. Plugin lifecycle management remains owned by Plugin Center.

## 14. Structural vs semantic validation

The JSON Schema answers whether the manifest has the expected shape. Kernel semantic verification additionally answers whether its claims are true and usable.

Examples that can pass structural validation but still fail semantic verification:

- `runtime.entry` names a missing file.
- `runtime.kind=toolpkg` points to an invalid ToolPkg.
- inner ToolPkg identity/version differs from outer manifest.
- an Extension Point exists syntactically but is not registered by Kernel.
- a dependency is declared correctly but is absent/incompatible.
- a Plugin Capability conflicts with a reserved/registered identity.
- a valid signature uses an untrusted publisher.

Schema success is therefore necessary but not sufficient for `INSTALLABLE`.
