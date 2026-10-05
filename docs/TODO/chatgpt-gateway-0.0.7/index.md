# ChatGPT Gateway 0.0.7

- Fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
- Repository: AdrienFan/AI-Limbs-Public-Build
- Branch: fix/bridge-receiver-output-pages
- Previous behavior: sequential execution and one-shot response posting; JSON-only results; notifications acknowledged without cancellation.
- Goal: preserve Host policy authority while adding durable delivery, bounded concurrency, cancellation, native media and paged results.
- Scope: ChatGPT child extension, its regression tests, package version and cloud workflow. No Host protocol changes.
- Validation: cloud-only JVM tests and signed Android package build.

1. [DONE] Result adaptation and encrypted storage.
2. [DONE] Request receipts, delivery, cancellation and lifecycle.
3. [DONE] Tests, documentation, version and cloud build entrypoint.
4. [TODO] Verify cloud test/build result and installation on the device.
