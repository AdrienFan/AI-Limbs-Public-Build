# LEV Mutual Guardian Probe

Two independent Android application IDs / UIDs test whether one app can wake the other while Samsung Freecess/LEV is active.

- Guardian A: `com.ailimbs.mutualprobe.guardian`
- Target B: `com.ailimbs.mutualprobe.target`

Both use a foreground service, request battery-optimization exemption, persist a 2-second heartbeat, and send an explicit cross-package PING every 2 seconds. The exported receiver records receipt, acquires a short wake lock, re-arms its own foreground service, and sends an ACK.

Test both apps with battery optimization ignored, start both services, then lock the screen and compare app evidence with Samsung Freecess `FZ/UFZ` logs.
