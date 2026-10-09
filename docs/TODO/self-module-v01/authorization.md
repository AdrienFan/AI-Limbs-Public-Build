# Multi-select lifecycle authorization — build115

Repository: https://github.com/AdrienFan/AI-Limbs-Public-Build

A human selects upgrade/migrate/rollback, ONE_TIME/TIMED/LONG, duration in hours if timed, and a reason. ONE_TIME includes exact package/version/target parameters. The form validates all selected items before publishing one PENDING request per operation with a shared batch_id. A crash during publication can leave a subset pending; none execute automatically. Each request is individually reviewed via ai_limbs.self.operation.review(request_id, approve, mode?, duration_seconds?, reason?).

A one-use approval executes the bound operation. A timed/long approval opens authority for later explicit human operations, never replays old pending applications. AI can independently reject items and shorten duration. LONG may become TIMED; TIMED cannot become LONG or exceed the requested duration. A pending authorization lacks exact operation parameters and cannot be converted into one-use execution; submit an exact one-use application for that case.

TIMED begins at actual approval, accepts 1..31536000 seconds. The UI accepts 1..8760 hours. Every human execution rechecks policy under the lifecycle lock. Observed expiry/revocation is durable and clock reversal cannot revive expired grants. A privileged attacker controlling filesystem/time remains outside the controlled-process threat model.

Policy is authorizations.json outside program versions and module data. Grants bind identity_id and data_id, so version switching preserves current policy; new migration data slots do not inherit authority. Only trusted AI review can approve, and ai_limbs.self.authorization.revoke(operation, reason?) can revoke ongoing grants. The human control plane exposes status, applications, cancel, first install and policy-checked execution; it cannot grant/revoke or fake AI origin. Existing Host admin authority/scopes, import staging and Dispatcher platform policy remain enforced.

An approved export or prepare persists human_migration_authorized in the SEALED slot in the same atomic commit. That authorization covers only continuation of this migration through signed receipts, including safe cancellation, even if an ongoing grant expires/revokes. Activation/cancellation clears it. Source committed transfers stay SEALED; target active state retains no source grants. Ordinary uninstall is still forbidden.

The Plugin Center owns UI: current actual grants, collapsible multiselect form, explicit operations and approval history. Approval display uses actual grant expiry, not requested duration. A generic attention projection includes only current effective TIMED/LONG grants and asks the AI to mention them in its reply; no ongoing grants means no authorization attention. This supplies authoritative model context, not a claim that external chat clients are forced to render text.

Tests add per-item partial decisions, scope escalation refusal, approval-relative expiry, expiry on execution, clock reversal, ongoing actions without stale pending replay, restart/revocation/rollback, migration continuation and destination defaults, bundle validation, package identity/schema checks and AI-origin forgery refusal. Run in Ubuntu cloud CI; device UI/Host/Resident validation remains a separate acceptance step.
