---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# Self-module experience database foundation

Baseline: base build119, module 0.1.4, clean development worktree f4430b8d9f9a2596b0af773f868aa481150feecf. Planned versions: base build120 and module 0.1.5. The existing module identity, data, program history and grants must be preserved.

## Intent and scope

Implement the Laner experience proposal v1.1 inside the self module. Its eleven business tables, supporting relations, temporal recognition and relationship rules belong to the module's storage assets. The kernel provides only generic active-slot SQLite execution. Database bytes reside under the module data_id directory; no tables are added to AppDatabase or ordinary plugin stores.

The current .ails blank entry does not execute cognition or collect conversations. This task adds the database foundation and a controlled initialization/read interface. Semantic recording and background AI remain later work.

## Plan

1. Read the work manual and architecture map; resolve source with ail-source and verify the live module. [DONE]
2. Add the module-owned descriptor, migration, eleven core tables, support tables, reference constraints and temporal views.
3. Add generic SQLite initialization/describe/read under the existing lifecycle lock and trusted ingress. Keep source-owned migration checksums and independent database schema versions.
4. Package 0.1.5 and build120 through cloud workflows; run isolated SQLite and existing lifecycle/authority regressions.
5. Check the installed database path, real schema, repeat initialization and retention of identity/data/grants. Restore the phone to ChatGPT before returning.

## Compatibility

This is an additive iteration. Existing lifecycle APIs, UI and .ails admission remain usable. Lifecycle schema stays 1. Existing blank packages have no storage descriptor; their retained database is neither rewritten nor deleted. Controlled migration changes local data_id while preserving identity and opaque bytes, so database ownership follows identity rather than a source-local directory ID.

Do not reinterpret hypotheses as agreements or backfill unknown historical decisions. Initialization creates empty business tables. Recording arbitrary SQL writes and automatic conversation capture are outside this iteration.

## Validation status

Implementation and cloud/device validation are in progress. No successful build, schema execution or deployment is claimed here yet. Details of the module contract are in [STORAGE.md](../../../self-module/STORAGE.md).
