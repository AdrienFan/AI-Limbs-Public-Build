---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
component: chatgpt-native-probe
version: 0.0.29
---

# One feedback for known sequential UI steps

Bridge owns sequencing for 1..8 explicitly supplied native UI actions. Host owns
all native authorization and a generic post-completion feedback read. Resolve all
addresses and final feedback support before executing. Suppress intermediate
feedback, stop at first failure/uncertainty, and read a fresh final frame without
replaying any operation. Preserve cancellation, receipt deduplication and retrying
only persisted response delivery. Return each received outcome and separate image
feedback failure. No automatic rollback or operation retries.

Matched base build111 and visual 0.2.4 are required; refresh MCP metadata for the
new ai_limbs_capability_batch tool. Keep existing invocation interfaces. Define
regressions for order, stop, malformed input, unresolved support, cancellation,
uncertainty and duplicate delivery. Cloud tests only; device verification remains.
