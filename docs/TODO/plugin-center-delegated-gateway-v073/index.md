# Plugin Center delegated gateway (V0.7.3)

Status: implementation complete; commit and build deferred for the combined bug batch

Baseline: `dev/v0.7.3`, forked from `dev/v0.7.2@b1bc27c`

Goal: keep the Stable Kernel mechanism-only while making Plugin Center.ailpsys the sole first-level permission control plane for parent plugins.

Steps:

1. [Host ABI and caller-aware Service Bus](01-host-service-bus.md)
2. Synchronize the public in-process SDK contract with Plugin Lab.
3. Restore Plugin Center before ordinary parent plugins and stop it after them.
4. Verify the forbidden Hub-to-Kernel gateway is absent.

Deferred: late Hub provider discovery in Bridge and `core.bridge.remote.invoke` implementation.
