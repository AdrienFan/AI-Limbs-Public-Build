# Experience database foundation 0.1.5

This module carries the SQLite schema for the Laner experience proposal v1.1. The Host supplies generic SQLite execution and the existing self lifecycle lock. It contains no experience table names, relationship rules or cognition logic. Base build120 is required for the new execution interface.

## Ownership

- The module owns [the descriptor](storage/database.json) and [migration 001](storage/migrations/001.sql).
- Packaging converts complete SQL statements into integrity-covered resources/storage/migrations/001.json and includes resources/storage/database.json.
- Runtime storage is data/<current data_id>/storage/laner_experiences.sqlite3 beneath the existing self-module store.
- The App Room database, ordinary-plugin store, slot metadata, state.json and ongoing authorizations are separate.
- The module retains the blank program entry and state_schema_version 1. Its database independently has schema_version 1.
- No conversation, personality, relationship label or actor profile is inferred or seeded during initialization.

The module's durable identity owns the database. A controlled transfer may allocate a different target-local data_id; created_data_id records the database's origin and is not an ownership check. Upgrade, rollback and signed migration retain the opaque database through existing lifecycle rules. Old lifecycle-only packages do not declare this database and do not open, reset or delete it.

## Execution interface

- ai_limbs.self.database.describe reads the verified active descriptor and reports initialization, path, schema version and actual schema objects. It does not create the database.
- ai_limbs.self.database.initialize applies the active package's migrations atomically and records their versions and SHA-256 values. Repeating it with unchanged migrations leaves experience rows intact. A modified applied migration or a newer on-disk schema is an error.
- ai_limbs.self.database.query accepts sql, optional string-or-null bindings and a limit from 1 to 200, default 100. The connection is native OPEN_READONLY. Queries have a five-second cancellation timer, bounded response size and an explicit truncated flag.

These interfaces reuse the trusted AI ingress and ordinary execution policy. Human pages and ordinary plugin callers do not acquire AI authority. No API for automatic conversation collection, semantic classification or unrestricted data writes is introduced in this iteration.

Every operation obtains the self lifecycle lock, resolves the active identity and data directory, verifies the current immutable package and closes all SQLite connections before releasing the lock. A sealed or departed module cannot perform a database operation. Writers use DELETE journal mode; migration export does not race an outstanding database connection or omit an active WAL.

The SQL boundary rejects ATTACH, DETACH, PRAGMA, VACUUM and extension loading from module migrations or query inputs. Module migrations cannot modify the Host's __ails_ metadata. Connection policy and migration transaction control remain with the generic executor. The business schema is not copied into Kotlin.

## Schema

The eleven core tables are actors, events, source_records, event_participants, event_links, decisions, executions, observations, interpretations, relationships and relationship_versions.

Supporting tables hold contexts, scopes, aliases, recognition-series identity, precise source links, evidence references, decision basis, additional decision/execution actors and relationship events. The generic executor creates two private migration/identity metadata tables. Multiple values are represented by rows with real foreign keys.

The relationship version extends one recognition version. Deferred reciprocal foreign keys enforce the one-to-one extension at transaction commit. A recognition series fixes the judge, target and scope. A new relationship stage uses a new series; correction within a stage uses previous_version_id. Stage links cannot cross the relationship or judge. Recursion checks reject revision, stage and subevent cycles.

JOINT_EXPLICIT requires two evidence references for the actual relationship endpoints. Each confirmation references an available raw record attributed to that speaker. These constraints validate source identity, not the meaning of the words: a semantic recording layer must still determine whether an expression actually confirms the relationship. That layer is outside this database-foundation task.

relationship_history preserves all recognition versions. relationship_current retains unresolved concurrent candidates and exposes concurrent_candidate_count. It separates judge, dimension, context and scope, checks currently known/effective times and marks uncertain temporal boundaries. confirmation_sources_available becomes false when a joint assertion's source cannot be verified. It does not choose the last inserted row or turn a single person's inference into joint agreement.

Historical queries must distinguish records available at the requested knowledge time from present-day reinterpretations of earlier valid time. Decision basis pins the exact recognition version read or adopted. An old decision does not change its evidence when a relationship is revised.

## Verification

The [standalone package validator](../tools/check_self_module_database.py) executes the packaged schema in isolated SQLite on the cloud runner. It exercises source deduplication, multiple roles, attribution, history, new stages, correction, restart, independent views, evidence, cycles, execution retry and delayed feedback. Fixture data never goes into the device database.

The Android cloud workflow compiles the generic executor and runs SQL-boundary tests plus lifecycle/authority regressions, including retained self data through upgrade and controlled transfer. Native creation, repeat initialization and read-only query must be checked on the installed base before claiming deployment complete.
