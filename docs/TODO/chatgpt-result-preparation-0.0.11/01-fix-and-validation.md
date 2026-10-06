# Correction and validation

The adapter now propagates oversized-result limit and cache-write exceptions to the engine. The engine records one preparation failure and returns `isError: true` with `execution_state: RESULT_RECEIVED`, `host_result_received`, `host_result_failed`, `result_delivery_error: RESULT_ADAPTATION_FAILED` and `automatic_reexecution: false`. Existing Host policy remains attached. The known Host outcome is retained in its own success or refusal counter.

Keep the failing disk-write integration test and strengthen its invoke, failure and error-code assertions. Update the direct oversized-result test to require propagation rather than the old successful envelope. Add a direct cache-write case and a tunnel integration case covering oversized successful and ASK-refused Host results, policy retention, separate counters and duplicate requests without repeated invocation. There are 31 regression test definitions.

Version is 0.0.11 with versionCode 11 and payload application ID `com.ai.limbs.payload.chatgptprobe.v011`. Check source diff, version consistency and JSON statically. Execute compilation and regressions only in cloud. Installed-device acceptance remains a later stage.

[DONE] Source correction, regression definitions and version/documentation updates complete. Cloud execution remains pending until the new dispatch; no local compilation or unit tests were run.
