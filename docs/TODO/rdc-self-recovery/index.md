---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
status: implementation
---

# RDC self-recovery

## Existing problem

An RDC device can keep a fresh REST `last_seen` while its Realtime channel is no longer usable. That creates a fake-online state: the Android bridge looks healthy but remote calls cannot enter the device.

## Intent

Make the native Android RDC provider prove Realtime round-trip health, expose an explicit recovery action in Bridge Center, and serialize recovery so other bridge lifecycle actions cannot race channel replacement.

## Scope

- `AiLimbsRdcClient` recovery transaction, session preservation, timeout, retry backoff
- `AiLimbsRdcRealtimeTransport` Phoenix heartbeat acknowledgement and forced stale-socket teardown
- provider-neutral `RECOVERING` / `RECOVERY_FAILED` states and `RECOVER` action
- Bridge Center recovery UI and lifecycle lock
- notification status updates while keeping recovery out of notification actions

## Build policy

No local Android build is run for this change. V0.6.3.8 is pushed to GitHub for cloud compilation after static preflight.
