---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
---

# Self-module experience database foundation

Baseline: base build119, module 0.1.4, clean development worktree f4430b8d9f9a2596b0af773f868aa481150feecf. Delivered versions: base build120 and module 0.1.5. The existing module identity, data, program history and grants must be preserved.

## Intent and scope

Implement the Laner experience proposal v1.1 inside the self module. Its eleven business tables, supporting relations, temporal recognition and relationship rules belong to the module's storage assets. The kernel provides only generic active-slot SQLite execution. Database bytes reside under the module data_id directory; no tables are added to AppDatabase or ordinary plugin stores.

The current .ails blank entry does not execute cognition or collect conversations. This task adds the database foundation and a controlled initialization/read interface. Semantic recording and background AI remain later work.

## Plan

1. Read the work manual and architecture map; resolve source with ail-source and verify the live module. [DONE]
2. Add the module-owned descriptor, migration, eleven core tables, support tables, reference constraints and temporal views. [DONE]
3. Add generic SQLite initialization/describe/read under the existing lifecycle lock and trusted ingress. Keep source-owned migration checksums and independent database schema versions. [DONE]
4. Package 0.1.5 and build120 through cloud workflows; run isolated SQLite and existing lifecycle/authority regressions. [DONE]
5. Check the installed database path, real schema, repeat initialization and retention of identity/data/grants. Restore the phone to ChatGPT before returning. [DONE]

## Compatibility

This is an additive iteration. Existing lifecycle APIs, UI and .ails admission remain usable. Lifecycle schema stays 1. Existing blank packages have no storage descriptor; their retained database is neither rewritten nor deleted. Controlled migration changes local data_id while preserving identity and opaque bytes, so database ownership follows identity rather than a source-local directory ID.

Do not reinterpret hypotheses as agreements or backfill unknown historical decisions. Initialization creates empty business tables. Recording arbitrary SQL writes and automatic conversation capture are outside this iteration.

## Validation status

Implementation commit: 0e28985bfd26d042cf0d6f57897ec8c00dc2eb3e on feat/self-module-database.

The module package workflow [38349470371](https://github.com/AdrienFan/AI-Limbs-Public-Build/actions/runs/38349470371) succeeded. Both fresh and identity-bound 0.1.5 packages passed integrity/UI validation and all 28 isolated SQLite checks, including eleven empty business tables and 21 module schema tables. The bound package SHA-256 is 12df5892de345386a3be1059adbd3a0808535d52a8071253f74d901a38eef184.

The base workflow [38349475802](https://github.com/AdrienFan/AI-Limbs-Public-Build/actions/runs/38349475802) passed canonical contracts, JVM regressions and instrumentation compilation. The device is now running build120, as verified by the live capability resolver. Native deployment and database validation completed on 2026-10-13. Details of the module contract are in [STORAGE.md](../../../self-module/STORAGE.md).


## Device verification

The upgrade installed module 0.1.5 ACTIVE, retaining identity 4d091ec7-a9db-43e8-b80b-d06b5bd03e30 and data_id 01f3f47c-85fd-4f94-8e62-5346d3cc1a60. Versions 0.1.1 through 0.1.5 remain in program history. The existing upgrade and rollback LONG grants remain ACTIVE.

Database describe initially reported declared=true, initialized=false. Initialization then created schema 1 with 21 module tables, four views and fourteen triggers. The generic migration and identity tables are separate from that count. Foreign-key enforcement is enabled. A second initialization succeeded without reapplying migration 1; the applied migration count remains one. Native SQLite reports 3.44.5. All eleven business tables have zero rows, with no fixtures or inferred memories seeded. An attempted DELETE through the query interface was rejected as SELF_DATABASE_QUERY_READ_ONLY.

The actual database path is /data/data/com.ai.assistance.operit.ailimbs.stable/files/ai_limbs/self-module/data/01f3f47c-85fd-4f94-8e62-5346d3cc1a60/storage/laner_experiences.sqlite3. This is the self module's data directory, not the App Room database or an ordinary plugin store. The phone was returned to com.openai.chatgpt after verification.

## Transfer fault and remediation

The original identity-bound cloud package is 13,965 bytes, SHA-256 12df5892de345386a3be1059adbd3a0808535d52a8071253f74d901a38eef184. Its manifest and program both declare 0.1.5, and ZIP/CRC validation passes.

The initial Linux-to-Android native.copy_file delivery produced 24,809 bytes, SHA-256 2df6149fbf640f3185a4199c568b5cc4e77c35ab49a8a94454ed4b3432643334. Those damaged bytes exactly equal original.decode('utf-8', errors='replace').encode('utf-8'). The user's upgrade page showed zip END header not found; Python validation reported Bad offset for central directory. This was transfer corruption, not a schema or version failure. Checking only the copy tool's success flag was insufficient.

Remediation used binary file copying through Ubuntu's shared Android storage mount. Both /storage/emulated/0/Download/laner-self-0.1.5-verified.ails and the original Download filename were verified byte-for-byte against the cloud package before the trusted lifecycle upgrade succeeded. The damaged transfer was retained under /root/laner/downloads/self-module-database/damaged-transfer-0.1.5.ails for diagnosis.

The generic cross-environment copy defect remains a separate follow-up; no copy-tool source change or additional base build is included in this database iteration. Future binary deliveries must verify destination size, SHA-256 and archive integrity, without a text encoding round trip. Automatic conversation capture and semantic recording remain unimplemented.
