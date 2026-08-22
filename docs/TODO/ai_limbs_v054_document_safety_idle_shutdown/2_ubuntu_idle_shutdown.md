---
title: Add Ubuntu idle shutdown policy
---

# Add Ubuntu idle shutdown policy

## Existing behavior

Ubuntu starts and stops through the lifecycle manager. A stopped runtime is not implicitly started by ordinary terminal commands. There is no configurable inactivity deadline.

## Intended behavior

- Persist a policy chosen from 10, 15, 30, or 60 minutes, a validated custom number of minutes, or long-running mode
- Preserve existing behavior by using long-running mode for existing installations
- Count only runtime inactivity, never silence during an active foreground operation
- Reset the deadline on commands and direct terminal input
- Stop through `stopUbuntu()` after the deadline
- Cancel the pending deadline when the runtime stops and defer expiry while work is active
- Show the policy controls near the Ubuntu lifecycle controls

## Verification

- Long-running mode never schedules an automatic stop
- Preset and custom values persist
- Invalid custom values cannot be applied
- Active commands block automatic stop
- New activity postpones the deadline
- Expiry follows the normal `STOPPING` to `STOPPED` lifecycle

[DONE]
