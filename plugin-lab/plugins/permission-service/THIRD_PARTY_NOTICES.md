# Third-party code

The ADB pairing/client and content-provider compatibility code are adapted from RikkaApps/Shizuku, supplied by the user in Android Download/Shizuku-master/Shizuku-master. Apache-2.0 license is retained in ../../vendor/SHIZUKU-LICENSE.

The internal server uses Shizuku-API revision a27f6e4151ba7b39965ca47edb2bf0aeed7102e5. See ../../vendor/shizuku-api/UPSTREAM.md and LICENSE.

Changes include AI Limbs namespaces and key storage, bounded transport reads/timeouts, removal of credential logging, ADB SYNC upload, explicit Host-only Binder admission, and plugin-owned UI. Official Shizuku names, icons and permission declarations are not used as this product's identity.

First version targets arm64 devices with Android 11+ for wireless pairing. ADB/root activation remains required. External application authorization, Rish and external UserService are outside v0.1.0.
