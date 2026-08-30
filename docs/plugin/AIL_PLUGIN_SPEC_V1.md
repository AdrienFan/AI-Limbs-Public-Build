# AI Limbs Plugin Specification v1

Status: Normative Draft for AI Limbs 0.7.1
Format ID: `AIL_PLUGIN_V1`
Schema Version: `1`
Plugin API: `1`

This document is the normative contract for AI Limbs plugins. It defines what qualifies as a plugin, how a plugin is installed, verified, mounted, stopped, upgraded, rolled back, and removed.

The keywords MUST, MUST NOT, SHOULD, SHOULD NOT, and MAY are normative requirements.

## 1. Core principles

1. `.ailp` is an installation/distribution container, not proof of plugin identity.
2. A renamed ZIP, image, text file, APK, or arbitrary archive MUST NOT become a plugin merely because its filename ends with `.ailp`.
3. A candidate becomes installable only after AI Limbs Plugin Verifier accepts its structure, manifest, ABI semantics, dependencies, runtime, and trust state.
4. AI Limbs MUST NOT execute an installed plugin directly from its import source.
5. Plugin Store is the single source of truth for installed plugin code and versions.
6. A runtime payload such as `.toolpkg` is not a second top-level installation system.
7. Plugins MUST NOT bypass Execution Policy Engine, Dispatcher, or ToolPermissionSystem.
8. Manifest permission declarations are requests, never grants.
9. All runtime contributions MUST have an owner plugin and reversible handles.
10. External payloads MUST NOT receive Android `Application Context`, unrestricted Shell, or arbitrary Android API access.
## 2. Identity and naming

Every plugin MUST have a stable `plugin_id`. `display.name` is user-facing and MAY change; `plugin_id` is machine identity and MUST remain stable across upgrades.

`plugin_id` MUST match:

`^[a-z0-9]+(?:[._-][a-z0-9]+)*$`

The namespace `ai_limbs.*` is reserved for AI Limbs first-party/system identities and MUST NOT be granted to an untrusted third-party publisher.

Plugin Capability executable identifiers and aliases MUST use the reserved `plugin.*` capability namespace. Host tools, ToolPkg tools, MCP tools, and other host runtimes MUST NOT claim `plugin.*` identifiers.

A plugin manifest MAY describe roles, but a manifest MUST NOT self-elevate trust. In particular, writing `system` or `system_service` in `roles` does not make a plugin a System Plugin. System Plugin classification is assigned by Kernel trust policy and Plugin Store metadata.

## 3. Package model

An `.ailp` candidate is a ZIP-compatible container with `plugin.json` at its root. The extension is a discovery hint only.

Canonical layout:

```text
hello.ailp
├── plugin.json
├── payload/
│   └── runtime.toolpkg
├── signature/
│   └── manifest.sig
└── resources/
```
AI Limbs MUST copy the selected candidate into an AI Limbs-managed private staging area before authoritative verification. Verification MUST run against the managed staging copy, not the external source path.

After verification, an installable version is promoted into Plugin Store. Moving or deleting the original external `.ailp` MUST NOT affect the installed plugin.

Plugin code and mutable state MUST be separated:

```text
plugins/<plugin_id>/versions/<version>/   # immutable installed code
plugin_data/<plugin_id>/                  # durable mutable data
plugin_cache/<plugin_id>/                 # disposable cache
plugin_secrets/<plugin_id>/               # broker-controlled secrets
```

Uninstall MUST remove mounted code and runtime resources. Durable plugin data SHOULD be preserved unless the user explicitly requests data deletion.

## 4. Verification pipeline

The authoritative verifier SHOULD expose one `PluginVerificationReport` consumed by Plugin Center, tests, CLI/automation, and future AI tooling.

A candidate passes these stages in order:

1. `CONTAINER` — archive structure, root manifest, safe paths, size/file limits.
2. `MANIFEST` — JSON syntax and `plugin-manifest-v1.schema.json`.
3. `ABI_SEMANTIC` — API compatibility, identifiers, runtime semantics, payload identity, extension points, dependencies, conflicts.
4. `TRUST` — digest, signature, signer/publisher policy.
5. `RUNTIME_PREFLIGHT` — runtime-specific preflight that does not activate the plugin.

Only a candidate that reaches `INSTALLABLE` MAY enable the Install/Save action in Plugin Center.
The verifier MUST fail closed. Unknown required fields, unsupported ABI, path escape, invalid payload identity, undeclared runtime contribution, incompatible dependency, or invalid trust state MUST NOT be silently repaired.

User-visible metadata MAY be parsed for preview before full verification, but it MUST be labeled unverified until manifest validation succeeds. AI Limbs MUST NOT invent plugin names or descriptions.

## 5. Lifecycle

Normative lifecycle states for v1 are:

```text
INSTALLED
→ MOUNTING
→ ACTIVE
→ UNMOUNTING
→ DISABLED
```

Additional states are `PENDING_RESTART`, `FAILED`, and `QUARANTINED`. `DISCOVERED`, `VERIFIED`, and `INSTALLABLE` are verifier/install pipeline states rather than persisted runtime states.

Install and enable are separate operations. An installed plugin MAY remain disabled indefinitely.

`ACTIVE` means runtime mount plus every required Registration and Extension Binding completed successfully. A runtime that started but failed registration/binding MUST NOT be reported ACTIVE.

Mount MUST be transactional. On mount exception, timeout, extension bind failure, registration failure, or health failure, Kernel MUST revoke all owned handles, attempt runtime stop, record an explicit error code, and transition away from ACTIVE.

Stop MUST revoke owned Registration and Extension Binding handles even if runtime stop later throws or times out. If stop cannot be confirmed, Kernel MUST report failure rather than pretending the plugin is safely stopped.
## 6. Runtime Host and PluginContext

Trusted Kernel `PluginRuntimeAdapter` code MAY receive Android Context because it is part of the trusted host implementation. External plugin payloads MUST NOT receive Android Context directly.

External payloads receive a restricted `PluginContext` capability surface:

- `registrar`
- `serviceResolver`
- `capabilityInvoker`
- `eventBus`
- sandboxed `dataDir`
- sandboxed `cacheDir`
- `logger`
- approved `secrets`

Sandbox directory APIs MUST prevent absolute-path and parent traversal escape. Secret access MUST be mediated by a Kernel broker. Requested scopes do not imply approved secrets.

AI Limbs MUST NOT treat arbitrary in-process Dex/Jar loading as a normal external plugin runtime. Code that can directly call unrestricted Android/Java APIs defeats the PluginContext boundary. Android system extensions belong in the separate `android_extension` model with controlled IPC and Android signing.

Runtime kinds defined by v1:

| Runtime | Status in 0.7.1 baseline | Intended activation |
| --- | --- | --- |
| `toolpkg` | IMPLEMENTED | `hot` |
| `none` | INTERNAL/METADATA ONLY | varies |
| `declarative` | RESERVED | `hot` |
| `wasm` | RESERVED | `hot` |
| `android_extension` | RESERVED | `cold_extension` |

A reserved runtime name MUST NOT be accepted as executable merely because it is documented. Kernel must have a registered compatible Runtime Adapter.
## 7. Contributions, capabilities, and extensions

Every contribution MUST be registered through Kernel-owned registration APIs and MUST carry `owner_plugin_id`. Runtime code MUST NOT directly mutate global registries.

The manifest is the maximum declared contribution surface. Runtime registration of a capability, service, provider, or extension not declared by the manifest MUST fail with `REGISTRATION_NOT_DECLARED` or an equivalent stable error.

All model-callable Plugin Capabilities MUST enter the common execution bus:

```text
Plugin Capability Registry
→ Execution Policy Engine
→ ALLOW / ASK / FORBID
→ Dispatcher
→ plugin executor
```

No plugin-facing API may expose a direct executor path that bypasses Policy.

Providers and Capabilities are different concepts. A provider implements a stable subsystem contract; it does not automatically create a new model-callable tool. Example: a TTS provider can implement `ai_limbs.voice.tts.provider` while AI Limbs continues exposing only the stable `ai_limbs.voice.speak` capability.

Extension Points are Kernel-defined sockets. Plugins MAY implement declared Extension Points but MUST NOT create arbitrary `ai_limbs.*` sockets and expect Kernel support.

Extension binding is part of the mount transaction. Unsupported point, incompatible API, duplicate binding, or binder failure prevents ACTIVE and triggers rollback of the mount scope.

## 8. Permissions and trust

`permissions.requested_scopes` is a declaration of requested access. It MUST NOT be interpreted as a permission grant.

Kernel trust, Execution Policy Engine, ToolPermissionSystem, Android platform permissions, and user approval remain authoritative.
Signature verification MUST distinguish package integrity from publisher trust. A content digest proving bytes were unchanged does not by itself prove a publisher is trusted.

A plugin MAY be structurally valid yet untrusted. Plugin Center MUST present that distinction rather than conflating `VALID` with `TRUSTED`.

## 9. Dependencies

Plugin and service dependencies MUST be declared in the manifest. Missing mandatory dependencies MUST prevent enable/mount rather than fail later with an arbitrary runtime exception.

Dependencies are lifecycle contracts, not merely install-time checks. If an ACTIVE plugin loses a mandatory service/provider it depends on, Kernel SHOULD transition it out of ACTIVE or rebind it according to the Extension Point contract.

## 10. Upgrade and rollback

Installed code MUST use versioned storage. Upgrade MUST stage and verify the new version before activating it.

Normative upgrade sequence:

```text
stage new version
→ verify
→ install version slot
→ stop/unmount old version
→ mount/bind/health-check new version
→ set active=new, previous=old
```

If activation of the new version fails, Kernel SHOULD quarantine the failed version and remount `previous`. Rollback MUST use an already verified managed version; it MUST NOT re-import an arbitrary external file.

Mutable `plugin_data` MUST survive normal upgrade and rollback.

## 11. Plugin Center authority

Plugin Center is the single management UI for install, uninstall, enable/disable, update, rollback, trust, dependencies, permissions, and health. Plugins MAY expose their own functional pages/settings, but MUST NOT create a parallel plugin lifecycle manager.
## 12. Required user-facing metadata

`display.name` and `display.description` are REQUIRED in v1. They are publisher-provided metadata used by Plugin Center install preview, cards, and details.

AI Limbs MUST display these values as publisher claims, not Kernel-authored facts. AI Limbs MUST NOT synthesize a description from a filename, plugin ID, or plugin name.

`display.icon` is optional and, when present, MUST resolve to a safe relative entry inside the managed package.

## 13. Stable error families

Implementations SHOULD preserve stable machine-readable error codes. v1 reserves at least these families:

- Package: `INVALID_PACKAGE`, `PACKAGE_PATH_UNSAFE`, `MANIFEST_MISSING`.
- Manifest: `MANIFEST_JSON_INVALID`, `MANIFEST_SCHEMA_INVALID`, `FORMAT_UNSUPPORTED`, `SCHEMA_UNSUPPORTED`.
- ABI: `ABI_INCOMPATIBLE`, `PLUGIN_ID_INVALID`, `VERSION_INVALID`, `UNKNOWN_RUNTIME`.
- Runtime: `RUNTIME_MOUNT_FAILED`, `RUNTIME_MOUNT_TIMEOUT`, `RUNTIME_STOP_FAILED`, `RUNTIME_STOP_TIMEOUT`.
- Contributions: `REGISTRATION_NOT_DECLARED`, `CAPABILITY_CONFLICT`, `UNKNOWN_EXTENSION_POINT`, `EXTENSION_API_INCOMPATIBLE`, `EXTENSION_BIND_FAILED`.
- Dependencies: `DEPENDENCY_MISSING`, `DEPENDENCY_CONFLICT`.
- Trust: `SIGNATURE_INVALID`, `UNTRUSTED_PUBLISHER`, `PLUGIN_QUARANTINED`.
- Policy: `PERMISSION_DENIED`, `PLUGIN_CAPABILITY_UNAVAILABLE`.

Exact implementation codes MAY be more specific, but UI and automation MUST receive machine-readable codes plus human-readable messages.

## 14. Spec precedence and implementation gaps

This specification is the target contract for 0.7.1. Existing 0.7 code is an implementation baseline, not authority over the specification.

Known 0.7 gap at publication: `PluginManifestParser` currently permits missing `display` and optional `display.description`; 0.7.1 MUST tighten validation so `display.name` and `display.description` are required before INSTALLABLE.

New Plugin ABI behavior SHOULD update this specification and the manifest schema before or together with implementation changes. Silent ABI drift is prohibited.
