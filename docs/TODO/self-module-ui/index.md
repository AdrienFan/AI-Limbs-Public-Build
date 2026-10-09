---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# Package-owned self module UI

The previous module management page and summary were hardcoded in Plugin Center 1.3.43. The full-width summary pushed ordinary plugin controls downward. UI changes required a Plugin Center release.

The user authorized moving UI ownership into .ails and placing a compact module entry at the right of the console inventory/add controls. Plugin Center now owns placement, offline page mounting, native human/file transport and the explicitly agreed independent recovery entry. The blank module owns page/summary HTML, CSS, forms and interactions. Base owns admission, immutable-version selection, opaque verified resource read, authority and lifecycle commits.

## Scope

- Base build116, versionCode211, comparison applicationId .ailimbs.build116; stable update flavor remains .ailimbs.stable
- Plugin Center 1.3.44, versionCode48; payload package identity remains the existing system-plugin identity
- Blank self module 0.1.2, same persistent UUID; data schema remains 1
- Opaque resources/ admission and integrity checks in the kernel; descriptor, UTF-8 and rendering validation in Plugin Center; old packages remain valid
- Package-bound human calls checked under lifecycle lock; trusted AI routes unchanged
- Offline general HTML renderer with no network/file/general admin authority
- Native human confirmation for page writes; summary is read-only
- Small right-hand card; narrow screen or large fonts can wrap inventory and entry
- Minimal native installation/recovery and signed migration completion independent of page health

## Review and verification

Changes target the authoritative Ubuntu worktrees and are not installed on the phone. Existing 0.1.1 identity, data and approved grants must remain untouched during development.

Added cloud regression cases cover opaque resource reads, resource admission corruption, UI following upgrades/rollback with opaque data retention, stale UI rejection and target-local bindings after migration. Existing lifecycle, authority and ongoing-grant tests remain in place. Python packaging source parsed successfully; both embedded page scripts passed JavaScript syntax checking; HTML IDs and literal references were checked for duplicates/missing targets. No Android/Kotlin build or regression execution has occurred for this iteration yet.

Pending cloud and device checks: compile base and Plugin Center; run the selected regression suite; verify package hashes and both UI entries; install base/center/module in order; check wide/narrow/font-scaled layout, selectors, approvals, file pickers, native cancellation, stale pages, render failure recovery and source/target sealed migration. Ordinary-plugin regression checks remain required.

Implementation details and stable page transport are documented in [the module contract](../../../self-module/README.md). This checkpoint is source-complete and awaits cloud validation.

## Offline rendering repair and independent package 0.1.3

The installed 0.1.2 page and compact card showed Android WebView error documents. The native recovery entry remained available and the module identity and ACTIVE metadata remained readable. Plugin Center 1.3.45 replaces the synthetic HTTPS document loader with a Base64 memory document, retaining network/file/navigation restrictions and native human transport. Cloud instrumentation exercises the actual module HTML on Android rather than relying on script syntax alone.

The module version is now 0.1.3, same persistent identity and schema. A standalone self-module-package workflow owns package generation and validation. The base APK workflow stops embedding these artifacts; base program version remains build116 because no base runtime code changes. Source review is pending cloud rendering execution and real-device confirmation.
