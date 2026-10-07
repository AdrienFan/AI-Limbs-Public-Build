---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
component: chatgpt-native-probe
version: 0.0.27
---

# ChatGPT interaction latency attribution

## Observed behavior

The user observed 10–17 seconds per phone operation with a returned screen image.
Current controlled measurements did not consistently reproduce that delay:
the same HOME shell operation took about 1.9 seconds without image feedback and
4.5 seconds with it; an accessibility HOME operation with image took 6.0 seconds.
Four reads of the same 86,655-byte stored preview took about 2.9–4.8 seconds.
One of those responses spent about 3 seconds in the network POST.

The existing processing_ms includes Host invocation and result adaptation together.
Image previews are already JPEG quality 82 with a default maximum edge of 1024.
The Host capture timestamp excludes PNG encoding. Cache-wide encrypted record scans
exist in GatewayResults.save, but their responsibility for the original slow requests
is not established.

## Scope and intent

Instrument the ChatGPT child bridge only. Associate bounded timing samples with
resolved public capability IDs and separate resolver, Host invocation, adapter lock
wait/work, encrypted-cache scanning/writing and cached-result reads.
Keep counts and byte totals, never record identifiers or contents of cached records,
parameters, callback URLs or credentials.

Preserve image format/quality, cache limits/expiry/encryption, operation and delivery
outcomes, concurrency, cancellation, and wire protocol. This release does not claim
a latency fix.

## Validation and delivery

Added deterministic regressions for repeated scan attribution, privacy, bounded
timing samples, cumulative duration precision, failed cache-write attribution and
engine-to-status propagation without request or result contents.
Local Gradle tests/builds are not run. The existing chatgpt-native-probe cloud target
runs its unit-test suite, builds and signs version 0.0.27.

After installation, reproduce the slow operation and compare host_invoke_ms,
result_adapt_ms, result_adapter_wait_ms, cache_scan_ms, cache_write_ms and
response_post_ms. If Host time dominates, the next investigation belongs in the
Host/visual pipeline; if adapter time dominates, cache processing can be assessed
without guessing from total elapsed time.

[DONE] Diagnostic implementation and regression cases; cloud execution and device
verification are reported separately.
