# Blank self module presentation 0.1.2

The .ails package owns its management page, compact summary, labels, application forms and interactions. It contains no memory, personality or learning logic. Host lifecycle and approval APIs remain authoritative.

## Package contract

AIL_SELF_V1 remains compatible with lifecycle-only 0.1.0 and 0.1.1 packages. The kernel admits the blank program plus opaque resources/ entries covered by the complete integrity map. It does not interpret resource contents. This module supplies resources/presentation.json declaring api_version 1, runtime html, entry resources/index.html and summary_entry resources/summary.html. Plugin Center interprets that descriptor and requires strict UTF-8. The module packager bounds each page to 512 KiB and uses self-contained inline CSS and JavaScript.

self_resources returns the verified active program's resource index, requested opaque bytes encoded as Base64, and a binding consisting of identity_id, data_id, module_version, generation and package_sha256. It is a Plugin Center human control-plane read, not an AI approval endpoint. The base does not parse page fields, labels or layout.

The current kernel accepts the blank program and arbitrary integrity-checked resources/ entries. Each resource read is limited to 16 paths and 512 KiB of raw bytes. Migration includes these resources inside the immutable program package and history. Upgrade and rollback change the selected program, thus change the selected UI. Lifecycle-only historical versions remain valid and explicitly report no presentation.

## Offline page transport

A page posts a JSON string to AilsTransport.postMessage containing id, method and parameters. The response calls window.ailsReceive with a JSON string containing the same id and response. The page sends __ready after installing its receive callback. Network, remote navigation, frames, files, content URIs and storage are unavailable in the renderer.

Supported management methods:

- status: read the live lifecycle, history, application and effective grant snapshot
- choose_package: Android document picker; returns an opaque package_token, or cancelled
- submit: existing multi-item human application form, optional package_token for a ONE_TIME upgrade
- request: exact human operation request, optional package_token
- execute: human operation using an effective ongoing grant or authorized migration continuation, optional package_token
- cancel_request: cancel a PENDING human request
- save_migration: Android document picker and controlled export of the sealed migration package
- copy: user-confirmed clipboard copy of text
- back: return to Plugin Center

The summary uses status only. Its whole native hit area opens management.

Pages do not receive a general capability invoker, ordinary plugin admin access, AI review, revoke, autonomous AI operations or writable persistent state. A page cannot provide package_path, uri or program_binding. The container supplies SAF URIs and the active package binding. Persistent operations require an independent native confirmation showing the actual request. This attests human interaction; it does not replace or expand AI approval. AI autonomous calls remain unchanged.

The base checks program binding under the same lock as human request creation, cancellation or execution. A version switch or migration transition invalidates the old page. A stale request cannot mint authorization or bypass identity, schema, integrity and grant checks. All terminal AI decisions remain single-use.

## Compatibility and recovery

Use base build116, Plugin Center 1.3.44 and blank module 0.1.2 together. Base build115 deliberately rejects UI-bearing packages because its admission only accepts program/blank.json. Install the new base before upgrading the module.

Plugin Center keeps a separate native installation/recovery surface. It supports initial install, requesting migration preparation, authorized upgrade or a one-time repair application, rollback to stored older programs, and sealed migration completion/abort with the existing signed receipts. It does not recreate the former module management page when resources are absent. Recovery follows the same existing authorization and lifecycle checks and does not reset identity or data.

Package with tools/package_self_module.py --output self-module/artifacts/blank-self-0.1.2.ails --version 0.1.2 --identity <existing UUID>. Use --without-presentation only for legacy lifecycle fixtures. Do not generate a new identity for an upgrade.
