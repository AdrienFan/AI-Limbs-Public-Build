# Upstream source

Source: https://github.com/RikkaApps/Shizuku-API
Revision: a27f6e4151ba7b39965ca47edb2bf0aeed7102e5
API version: 13.1.5
License: Apache-2.0, retained in LICENSE.

AI Limbs changes: Gradle module names and dependency wiring; Rish Java types retained for upstream Service linkage, native Rish build omitted. The internal service rejects Rish and external user-service calls. It supports the existing Shizuku Binder protocol for Host shell/system operations only.

Service.java additionally redacts command/env values from process logs and restores Binder identity in a finally block around forwarded transactions.
