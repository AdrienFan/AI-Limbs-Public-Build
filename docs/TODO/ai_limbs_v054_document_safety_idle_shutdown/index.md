---
title: AI Limbs V0.5.4 document safety and Ubuntu idle shutdown
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# AI Limbs V0.5.4 document safety and Ubuntu idle shutdown

## Existing behavior

AI Limbs stores the editable access prompt and work manual in the Android app sandbox. The access manager exposes their filesystem paths, document write calls replace the active file directly, and the tool manual is not yet a managed Android document.

Ubuntu has explicit `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, and `ERROR` lifecycle states. It has no persisted idle-shutdown policy.

## Intent

- Keep system-owned access rules and manual entries outside editable document bodies
- Manage the tool manual beside the access prompt and work manual
- Preserve the previous three saved versions before replacing a document
- Restore a selected version without starting Ubuntu
- Remove implementation paths from normal UI and bridge payloads
- Deliver provider-neutral access context made from protected and editable sections
- Add safe Ubuntu idle shutdown with presets, a custom-minute option, and long-running mode

## Scope

The main app owns document storage, history, access-context composition, and recovery UI. The Terminal submodule owns Ubuntu activity state, the idle timer, persisted policy, and terminal controls. V0.5.3 remains unchanged on its existing branch.

[DONE]
