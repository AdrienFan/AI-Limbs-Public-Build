# 01 Recovery state machine

## Old behavior

Bridge actions were still available while reconnecting, and there was no distinct state for an explicit long-running repair. `REPAIR` meant re-pairing and cleared the saved RDC session.

## New behavior

- `RECOVER` is a separate action from `REPAIR`.
- `RECOVERING` locks all Bridge lifecycle actions and provider switching.
- The recovery transaction preserves the saved device/session and never silently enters pairing.
- Missing or invalid reusable credentials end in `RECOVERY_FAILED` with a re-pair message.
- Recovery has a 120-second deadline and returns the UI to an actionable failed state instead of locking forever.
- Automatic reconnect uses exponential backoff with jitter instead of a fixed five-second retry loop.

## UI contract

Bridge Center owns the repair button. The foreground notification reports recovery state but intentionally does not expose a recovery action.

[DONE]
