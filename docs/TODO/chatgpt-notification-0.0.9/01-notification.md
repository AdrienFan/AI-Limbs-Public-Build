# Notification contribution

Publish BridgeProviderNotification instead of null. Show the short bridge name, Chinese phase label, request and acknowledged response counts, successful communication time and a short attention message when needed.

Choose at most two actions for the current phase and intersect them with BridgeProviderControl.availableActions. Recheck availability and configuration at dispatch. Do not expose raw error details, credentials, tunnel IDs or configuration clearing on the lock-screen-visible surface.

Validate contribution wiring, version consistency, action limits and clean diff statically. The existing cloud workflow performs unit tests, compilation and signing. Actual notification layout and interaction require installation on the device.

The previous 0.0.8 cloud run stopped at Kotlin compilation because negating a Byte literal produced Int values in JPEG signature comparisons. Convert the stored bytes to Int explicitly, preserving the signed JPEG marker values. Include this build blocker fix with the notification iteration.

Add a JPEG regression case covering successful delivery of a real 1x1 fixture and partial media failure when its end marker is removed. The cloud workflow now has 20 cases. No local compilation or unit tests were run.

[DONE] Source wiring, version and action checks complete. Cloud result and installed notification validation remain pending.
