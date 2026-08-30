# AI Limbs Plugin Extension Points v1

Status: Normative registry/catalog
Plugin API: `1`

This file is the human-readable catalog of Kernel-defined extension sockets. A syntactically valid extension ID is not usable unless the point is listed here and registered by the running Kernel.

## 1. General contract

An extension declaration has the form:

```json
{
  "point": "ai_limbs.test.provider",
  "id": "sample",
  "api": 1
}
```

`point` selects a Kernel-defined socket. `id` identifies one implementation owned by the plugin. `api` is the exact extension contract version requested by v1.

Runtime registration MUST match a manifest-declared `(point,id)` pair. The runtime cannot invent a different Extension Point or API version during mount.

Binding is transactional and reversible. Successful binding returns an `ExtensionBindingHandle` owned by the same Plugin MountScope as the contribution handle.

When the MountScope is revoked, binding MUST be removed before the contribution record is discarded.

Unknown points fail closed. Documentation marked RESERVED is not an active point.
## 2. Active extension points in the 0.7.1 baseline

### `ai_limbs.test.provider`

- Status: IMPLEMENTED / DEVELOPMENT CONTRACT
- API: `1`
- Purpose: validates typed extension registration, binding, rollback, and ownership mechanics.
- Production user feature: none; this is not a general-purpose provider API.
- Cardinality: implementation-defined for tests; duplicate `(point,id)` bindings are rejected.
- Hot bind: yes.
- Permission elevation: none.

This point exists so Kernel mechanics can be tested without pretending that Bridge, Voice, or Memory extension APIs are already finished.

## 3. Reserved roadmap names — NOT ACTIVE

The following names are design reservations only. A plugin declaring one today MUST NOT be considered bindable until the corresponding subsystem contract is implemented and registered by Kernel:

- `ai_limbs.bridge.provider`
- `ai_limbs.voice.tts.provider`
- `ai_limbs.voice.stt.provider`
- `ai_limbs.memory.store`
- `ai_limbs.memory.retriever`
- `ai_limbs.memory.embedding`
- `ai_limbs.skill.evolution`

Reserved names prevent accidental naming drift. They do not promise API shape, cardinality, or compatibility.

When one becomes active, this file MUST add its contract before or together with implementation.
## 4. Required catalog entry for future points

Every newly activated Extension Point MUST document at least:

1. Point ID.
2. Extension API version.
3. Owning AI Limbs subsystem.
4. Provider/interface contract.
5. Cardinality: single, multiple, or selected-active implementation.
6. Selection/routing behavior when multiple implementations exist.
7. Required permissions/scopes.
8. Mandatory and optional dependencies.
9. Hot-bind capability and activation constraints.
10. Mount/bind failure behavior.
11. Health/unavailability behavior.
12. Unbind/disposal guarantees.
13. Whether provider disappearance causes consumer suspension, fallback, or failure.

A subsystem SHOULD expose a stable Provider contract behind a Core Capability rather than multiplying model-callable tools for each implementation.

Example intended pattern:

```text
ai_limbs.voice.speak        # stable Core Capability
        ↓
Voice System
        ↓
ai_limbs.voice.tts.provider # provider Extension Point
        ↓
selected implementation
```

Until the catalog entry exists, plugin authors should treat the extension as unavailable and MUST NOT depend on source-code internals as an unofficial ABI.
