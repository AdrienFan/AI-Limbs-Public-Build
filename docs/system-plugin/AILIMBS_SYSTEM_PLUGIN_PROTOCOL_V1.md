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
    "entry": "payload/plugin-center.apk"
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
    "signer_id": "ai_limbs.official",
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

V0.7.2 Bootstrap validation currently verifies package structure, role, Host ABI, integrity hashes, and signature-envelope presence. Cryptographic signer trust is intentionally reported as `NOT_EVALUATED` until the trusted system keyring is connected. Therefore Bootstrap validation success is not installation authorization.

## 9. Bootstrap behavior

The permanent AI Limbs Toolbox Bootstrap Slot is `order=0`. Before a Plugin Center system plugin is installed and bound, it displays a `+` entry. Selecting a candidate performs validation only in V0.7.2 phase 1.

A future install flow must re-run validation, perform trusted signer verification, commit transactionally, bind `system.role.plugin_center`, and only then replace the `+` slot with the Plugin Center-contributed entry.

## 10. Security invariants

- `.ailpsys` is not interchangeable with `.ailp`.
- A filename suffix never grants system authority.
- Plugin Center identity is role-based, not hard-coded to one plugin ID.
- Path traversal, duplicate archive entries, oversized manifests/entries, and hash mismatches are rejected.
- Runtime execution is separate from protocol validation.
- Signer trust is separate from integrity validation.
- System plugin lifecycle authority remains Host-controlled.
