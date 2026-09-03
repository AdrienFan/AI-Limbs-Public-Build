# Plugin Center delegated gateway integration

Status: implementation complete; commit and build deferred for the combined bug batch

Host baseline: `dev/v0.7.3`, forked from `dev/v0.7.2@b1bc27c`

Goal: make Extension Hub consume Plugin Center's versioned Service Bus gateway without any raw Kernel gateway.

Steps:

1. [Hub and SDK integration](01-hub-integration.md)
2. Update Developer Guide and package versions.
3. Keep existing SHA-256, Ed25519, lifecycle Mutex, and child packager behavior intact.
4. Verify Hub exposes only child publisher verification and delegated child capability calls.

Deferred: Bridge late-Hub discovery and `core.bridge.remote.invoke` implementation.
