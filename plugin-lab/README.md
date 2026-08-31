# AI Limbs Plugin Lab

Plugin Lab is the reference host used to develop and validate AI Limbs plugin contracts before they
move into the production application. The installed base keeps lifecycle, trust, permission
brokering, capability/extension routing, storage isolation, admin security, and Plugin Center.

Optional behavior is delivered as external `.ailp` packages. Validation fixtures such as
`headless-echo` and `runtime-log-viewer` remain in the repository for development, but they are no
longer bundled into the APK and there is no built-in sample installer in Plugin Center.

Plugin Center supports multi-file import, a pending-install queue, per-item removal, batch approval,
and batch installation. Uninstall and developer controls are protected by the administrator gate.

Administrator setup creates a password credential plus a one-time recovery key. The recovery key can
reset a forgotten administrator password without the old password. There is no universal bypass.

Developer mode exposes Host Surface Policy. Versioned extension points, host capabilities, and plugin
publication buses can be allowed or blocked. Manifest-known requirements are enforced before mount;
a running owner is revoked and becomes `BLOCKED` when a required surface is disabled, then is retried
automatically when the surface is restored. Invocation-only host capabilities are also checked at the
moment of every call.
