# Self identity and migration registration client — build117 / module 0.1.4

Repository: https://github.com/AdrienFan/AI-Limbs-Public-Build

Scope: independently allocated first-install identities, user-selected migration export,
metadata upload client, and controlled source-slot retirement after a matching acknowledgment.
No receiver database, global certification, memory, personality, or learning is implemented.

## Identity

New public module packages declare `identity_mode: CREATE`, `identity_id: null`, and
`module_id: ai_limbs.self.blank`. Under the unique-slot lock, initialization allocates a
UUIDv4, persists allocation before installation, and binds an immutable program to it.
Separate stores installing identical bytes get distinct IDs. Retry before commit preserves
the allocation. Reusing the template after departure creates a new individual. Upgrades
from templates bind to the existing identity; bound upgrades must match it. Rollback and
migration preserve it. New explicit BOUND packages are upgrade-only and cannot create a first installation. Existing V1 bound packages without identity_mode stay supported.
An identity belonging to an outgoing transfer cannot be installed again at its source.
Identifiers distinguish individuals; they are not proof against malicious copies.

## Export and registration

The module owns the HTML form and requests `choose_export` before a migration application
or execution. Plugin Center supplies an opaque export token, holds the SAF grant, and
injects only the user-selected content URI into the authoritative request. AI approval
executes the same pinned request. Immutable internal staging is retained for transaction
safety; it is not the user-facing destination. The host writes the selected document and
reads it back to verify byte count and SHA-256 before marking it saved. A failed write
keeps the source sealed and recoverable; explicit save retry/cancellation remain available.

The module's migration form accepts a configured HTTPS registration endpoint and an optional
ephemeral bearer token. Native confirmation displays endpoint, identity, migration ID, hash,
and the source-uninstall consequence. The native transport obtains registration fields
from the host rather than accepting a page-authored payload or success receipt. Redirects
are forbidden, connect/read timeouts are 15 seconds, replies are bounded to 64 KiB, and
bearer tokens are never written to durable state or emitted in messages.

POST JSON protocol `AIL_SELF_REGISTRATION_V1` contains identity_id, migration_id,
package_sha256, package_size_bytes, module_version, state_schema_version,
source_device_id and target_device_id. Idempotency-Key equals migration_id. It contains
neither program/data bytes nor local paths. The future receiver must authenticate access
and persist a record before acknowledging it. The client does not implement or claim that
receiver persistence exists.

Required response: HTTP 2xx, application/json, protocol AIL_SELF_REGISTRATION_V1,
received true, exactly matching identity_id/migration_id/package_sha256, and a nonempty
receipt_id of at most 256 characters. Missing configuration, errors, redirects, malformed
responses and mismatches never retire the slot. These responses acknowledge registration,
not target activation and not a backup of the complete package.

## Controlled source uninstall

Only the trusted control plane passes the transport-verified receipt to the lifecycle
substrate. A saved and intact package is required. The host commits a durable retirement
intent and moves the slot into a retained transfer ledger. Normal inventory reports no
installed self module. Program/history/data/export and the device signing key are retained
as recovery material, not activated as another installed instance. A crash after the intent
finishes the same retirement during recovery.

The independent recovery surface can continue a departed transfer with its migration_id.
Signed prepare/release/abort receipts remain mandatory. Source cancellation can restore
only into an empty slot; a committed release cannot be cancelled. Ordinary plugin uninstall
remains prohibited. Later destruction of recovery material is outside this iteration.

## Verification

Cloud-only SelfModuleStoreTest additions cover identical-template distinct IDs, upgrade/
rollback preservation, matching registration requirements, uninstall with retained data,
continued target activation, rejection of reinstalling a departed identity, and safe restore.
Plugin Center emulator tests cover bounded transport, wrong receipts, refusal, redirects,
oversized replies, invalid HTTPS endpoints, and refusing upload without verified save.
The existing real-WebView and ordinary-plugin regression suites remain enabled.

Versions: base build117 (versionCode 212), Plugin Center 1.3.51 (55), module 0.1.4.
Module packaging remains a separate workflow with no Android base compilation.
Implementation complete; cloud validation and real-device verification pending.
