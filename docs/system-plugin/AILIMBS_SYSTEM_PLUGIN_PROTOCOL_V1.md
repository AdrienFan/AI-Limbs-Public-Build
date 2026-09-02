# AI Limbs System Plugin Protocol V1

Status: V0.7.2 bootstrap contract.  Package extension: `.ailpsys`.

## 1. Purpose

`.ailpsys` is a distinct AI Limbs System Plugin package. It is not an `.ailp` with a different suffix. It is reserved for trusted system roles such as Plugin Center, Extension Hub, Host Adapter, Recovery, and other host-level services.

The filename suffix never grants trust. System authority comes from protocol validation, role validation, Host ABI compatibility, package integrity, and a trusted signer decision.

## 2. Archive layout

An `.ailpsys` file is a ZIP-compatible archive with safe relative paths only.

Required root manifest:

`system-plugin.json`

Typical layout:

```text
PluginCenter.ailpsys
├── system-plugin.json
├── payload/
│   └── plugin-center.apk
└── META-INF/
    └── AILIMBS.SIG
```

## 3. V1 manifest

```json
{
  "format": "AIL_SYSTEM_PLUGIN_V1",
  "schema_version": 1,
  "plugin_id": "ai_limbs.system.plugin_center",
  "version": "2.0.0",
  "display": {
    "name": "Plugin Center",
    "description": "AI Limbs system plugin management plane"
  },
  "system": {
    "role": "plugin_center",
    "host_abi": { "min": 1, "max": 1 }
  },
  "runtime": {
    "kind": "android_inprocess",
    "entry": "payload/plugin-center.apk",
    "entry_class": "com.ai.limbs.plugincenter.PluginCenterEntry"
  },
  "permissions": {
    "requested_scopes": []
  },
  "integrity": {
    "algorithm": "SHA-256",
    "entries": {
      "payload/plugin-center.apk": "<64 hex chars>"
    }
  },
  "signature": {
    "algorithm": "Ed25519",
    "signer_id": "ai-limbs-plugin-center-dev-v1",
    "entry": "META-INF/AILIMBS.SIG"
  }
}
```

## 4. System roles

V1 recognizes: `plugin_center`, `extension_hub`, `host_adapter`, `recovery`, `system_service`.

A Bootstrap Slot for Plugin Center accepts only `system.role=plugin_center`. It must not accept a normal `.ailp`, an `.ailx`, or another `.ailpsys` role.

## 5. Host ABI

V1 Host ABI is `1`. The current Host ABI must fall within `system.host_abi.min..max`. A package outside that range is rejected before install or execution.

## 6. Runtime

V1 protocol recognizes `declarative` and `android_inprocess`. The declared runtime entry must exist and must be included in the integrity map. Protocol recognition does not by itself authorize execution; the Host Runtime and trust policy decide whether that runtime may mount.

## 7. Integrity

V1 uses SHA-256. `integrity.entries` must cover every archive file except `system-plugin.json` and the detached signature entry, exactly once. Extra, missing, malformed, or mismatched entries are rejected.

The detached signature is intended to authenticate the exact manifest bytes. Because the manifest contains the payload hash map, a trusted signature over the manifest authenticates the payload tree without a recursive signature hash.

## 8. Signature and trust

V1 requires an `Ed25519` signature envelope and a non-empty detached signature file. The `.ailpsys` suffix and `signer_id` string never establish trust by themselves.

V0.7.2 Bootstrap validation verifies package structure, role, Host ABI, integrity hashes, then verifies the detached Ed25519 signature over the exact `system-plugin.json` bytes against the built-in trusted system keyring. Unknown signers, role/signature mismatches, and invalid signatures are rejected. A successful result reports `TRUSTED`.

## 9. Bootstrap behavior

The permanent AI Limbs Toolbox Bootstrap Slot is `order=0`. Before a Plugin Center system plugin is installed and bound, it displays a `+` entry. Selecting a candidate performs full protocol, integrity, role and trusted-signer validation before installation is offered.

Installation re-runs the same trusted validation, stages the candidate transactionally, mounts it, requires a healthy Plugin Center UI contribution, commits the active version, and only then replaces the Bootstrap slot with the Plugin Center-contributed entry.

## 10. Security invariants

- `.ailpsys` is not interchangeable with `.ailp`.
- A filename suffix never grants system authority.
- Plugin Center identity is role-based, not hard-coded to one plugin ID.
- Path traversal, duplicate archive entries, oversized manifests/entries, and hash mismatches are rejected.
- Runtime execution is separate from protocol validation.
- Signer trust is separate from integrity validation.
- System plugin lifecycle authority remains Host-controlled.

## 11. Dynamic Navigation Surface service

`PluginCenter.ailpsys` receives a Host-owned `navigation` JSON service through `SystemPluginHostV1`. Dynamic pages are identified by stable `surface_id` values; changing a page title does not change its identity.

V1 operations:

- `list_surfaces` / `describe_surface`
- `create_surface` / `rename_surface`
- `delete_surface`
- `list_contributions`
- `bind_contribution` / `unbind_contribution`

Ordinary plugins keep using the existing `PluginHomeTileSpec` and `PluginScreenSpec` contribution model. Plugin Center chooses which dynamic surface receives each active contribution; the page does not hard-code plugin IDs.

`delete_surface` is deliberately non-cascading. It requires an explicit administrator password on every call, and the Kernel rejects deletion while any binding remains. This prevents deleting a navigation page from deleting or silently detaching its contained applications.
