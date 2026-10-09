# AI Limbs Self Module V0.1 — source checkpoint

Date: 2026-10-09
Repository: https://github.com/AdrienFan/AI-Limbs-Public-Build
Baseline base commit: 3560a2e1b375
Baseline installed base: 0.8.0.19-build113
Baseline Plugin Center: 1.3.40
Prepared versions: base build114 / versionCode209; comparison applicationId suffix .ailimbs.build114; Plugin Center 1.3.41 / versionCode45; blank program 0.1.0.

## Scope and ownership

The ordinary .ailp pipeline does not own this slot. Special admission uses self.json, AIL_SELF_V1 and module_type=self. V0.1 permits only the declarative blank program entry. It does not load executable module code, cognition, memory, personality or learning.

SelfModuleStore implements host lifecycle substrate: one cross-process locked slot, immutable program versions, opaque module data directories, atomic pointer commits and request journals. Identity and minimum module state live under files/ai_limbs/self-module, independent of ordinary plugin stores and backups.

KernelPluginAdminJsonServiceV1 exposes human first install, applications, cancellation, state and narrow export. The human surface cannot review or execute an upgrade/rollback/migration directly.

AiLimbsDispatcher registers ai_limbs.self.status/install/upgrade/rollback/migrate/operation.review. Session transport comes from the Host execution context and must also carry an internal hostAttestedAiIngress receipt. The verified bridge owner and authenticated HTTP service mint it; Resident reconstructs it only for the actual current Host socket peer PID. PLUGIN_RUNTIME, caller-supplied transport enums and copied sessions cannot mint it. User JSON has no authority to supply it. Ordinary plugins cannot obtain AI authority by supplying initiator=AI. Existing platform ALLOW/ASK/FORBID policy remains in front of these operations.

Plugin Center owns the page. Its URI imports reuse the existing Host SAF staging path, including the Resident boundary. Existing .ailp/.ailx/.ailpsys paths retain their original formats.

## Approval transaction

Applications are durable PENDING records with a unique request_id. Package input is validated and pinned as an immutable byte snapshot. Each application binds to the current module state fingerprint. AI approval executes exactly this application once; rejection and cancellation become terminal. Absent AI never produces approval.

EXECUTING is persisted before execution. Slot commits carry last_completed_request_id. Restart status reconciles a committed request as COMPLETED_RECOVERED; an uncertain interrupted operation becomes RECOVERY_REQUIRED and cannot be silently replayed. Original data is retained.

## Controlled migration

1. Target self.status supplies its device_id, derived from its device signing key.
2. Source migrate/export builds a hashed .ails migration archive, including current program, retained program history and every data file. Source atomically enters SEALED before the package is exposed.
3. Target migrate/prepare checks destination identity, archive integrity, nested program identity, schema and opaque data metadata. Its slot stays SEALED. It returns a signed PREPARED receipt with an unpredictable nonce.
4. Source migrate/commit checks the signed destination receipt, package hash and migration_id. It durably commits transfer_committed=true and a signed RELEASED receipt. Source remains SEALED.
5. Target migrate/activate accepts only the matching signed source release, package hash, migration_id, device_id and target nonce. Only then does it become ACTIVE.
6. Before commit, source migrate/cancel restores ACTIVE and issues a signed ABORTED receipt. The abort is persisted in source state before returning, so restart cannot lose the cancellation evidence. Target migrate/discard accepts that abort before releasing its inactive slot. Data is archived, never destroyed.

After successful commit the source keeps a SEALED recovery copy and its slot. V0.1 does not automatically delete that copy or release the source slot. A committed transfer cannot be locally cancelled, because doing so could create two active instances. Release retrieval is idempotent and state/receipts remain available after restart.

Without a shared authentication service this is controlled transfer, not a claim of protection against arbitrary disk copying, reinstalling identity packages elsewhere, device-key extraction or modification by a privileged local actor.

## Package creation

Source: self-module/blank.json.
Tool: tools/package_self_module.py.
A new first-install package creates a UUID. To package an upgrade, pass the currently installed identity with --identity; never generate a different identity for an upgrade.
Module version must increase for upgrades and decrease for rollback. Old program must explicitly list the current state schema as compatible.
Package creation and compiler execution are deferred until the user's additional requirement is integrated.

## Acceptance plan

SelfModuleStoreTest covers first install, second identity refusal, renamed ordinary archive rejection, same-identity upgrade/rollback and opaque data retention, one-use approval/rejection, waiting/cancellation, stale applications, pinned packages, schema/identity failure, migration handoff, abort, wrong/occupied targets, signature tampering, concurrent slot contention, interrupted-operation reconciliation and archive tampering/traversal.

SelfModuleAuthorityTest covers parameter forgery by plugin transport and direct human execution/review refusal.

Still required before release:
- Cloud Kotlin compilation and JVM test execution.
- Existing ordinary plugin regression tests.
- Host and Resident device installation, SAF imports/exports, UI and approval attention checks.
- Two isolated AI Limbs suites for the end-to-end transfer and crash scenarios.
- Package artifact hash/version verification.

## Current verification status

Implementation and test source are prepared. No executable acceptance test is claimed as passed. No Android APK or .ailpsys has been built for this change.

User steering: pause ALL git pushes, workflow dispatches and cloud compilation until the additional requirement has been integrated and the user resumes compilation. No current device was upgraded or installed during this source task.

## Resumed authorization iteration

User authorized implementation, push and cloud compilation on 2026-10-09. Add multi-select human applications, per-item AI decisions, TIMED/LONG authorization, revoke, persistent current-grant display and feedback attention. ONE_TIME remains exact-operation approval. Grants are host policy outside program versions/data; migration continuation retains transaction authorization, destination starts without ongoing grants. Update base to build115/code210 and Plugin Center to 1.3.42/code46. Run lifecycle/authority/grant regressions on Ubuntu GitHub Actions, package blank .ails and signed Plugin Center after successful cloud build. No local compilation.
