# Validation

- Package, APK and gateway source versions: 0.0.7, versionCode 7.
- Existing extension ID, Bridge API 5, source package name and signing secret preserved.
- Static checks: Kotlin delimiter scan, package JSON parsing, cloud YAML parsing and required unit test/signing steps.
- Regression coverage: 19 JVM cases, including concurrent control traffic, request duplicates, cancellation, execution deadline, delivery retry, uncertain recovery, media and Unicode pages.
- No Android compilation or JVM tests run locally; execution is cloud-only.
- Device validation pending: actual Android Keystore persistence, screen-off/network recovery, ChatGPT tool-catalog refresh and native images.
- The active installed plugin is still 0.0.6 until the cloud-built extension is installed.
