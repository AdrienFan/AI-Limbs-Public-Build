# AI Limbs Plugin Standard

Start here when developing or reviewing AI Limbs plugins.

Reading order:

1. `AIL_PLUGIN_SPEC_V1.md` — normative platform rules and lifecycle/security model.
2. `AIL_PLUGIN_MANIFEST_V1.md` — exact `plugin.json` field contract.
3. `AIL_PLUGIN_EXTENSION_POINTS_V1.md` — active and reserved subsystem sockets.
4. `plugin-manifest-v1.schema.json` — machine-readable structural contract.
5. `AIL_PLUGIN_DEVELOPER_GUIDE_V1.md` — implementation workflow and acceptance checklist.

Do not treat `.ailp` filename extension as plugin identity. A candidate becomes installable only after authoritative verification.

For Plugin Center user-facing install preview:

- plugin name comes from `plugin.json -> display.name`;
- functional description comes from `plugin.json -> display.description`;
- AI Limbs must not invent either field.

Current 0.7.1 baseline runtime status:

- `toolpkg`: IMPLEMENTED Hot Runtime.
- `declarative`: RESERVED.
- `wasm`: RESERVED.
- `android_extension`: RESERVED.

When implementation and these normative documents disagree, record the mismatch and bring implementation into conformance or explicitly revise the specification. Do not silently create an undocumented ABI.
