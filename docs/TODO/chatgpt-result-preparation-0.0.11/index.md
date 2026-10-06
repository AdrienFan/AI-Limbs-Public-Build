---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: fix/bridge-receiver-output-pages
---

# AI Limbs-ChatGPT 0.0.11 result preparation fix

The 0.0.10 cloud run compiled Kotlin and executed 29 regressions. One integration test failed because the old oversized-result cache handler swallowed an injected write failure and produced `isError: false`. The engine never observed the exception, so the intended result preparation counter and explicit delivery error were missing.

Remove that internal catch and let the existing engine preserve the already received Host outcome while reporting preparation failure. A missing freshly cached page follows the same path; a separately requested expired page keeps its existing read-error behavior. Do not repeat the originating Host capability or change its permissions.

Scope is the ChatGPT child adapter, engine, regressions, package version and documentation. Six tool names, extension identity, Bridge API 5 and parent behavior remain unchanged.

Implementation and validation: [01-fix-and-validation.md](01-fix-and-validation.md).
