# Shared-screen geometry and frame contract

Fork: https://github.com/AdrienFan/AI-Limbs-Public-Build

Scope: MediaProjection capture substrate, Standard UI capture, Host screen frame envelope and generic UI display preconditions. No visual-plugin identity or bridge-specific visual logic enters Host.

Build112 fixes stale capture dimensions after rotation and captured-content resize by resizing the same VirtualDisplay and replacing its Surface/ImageReader. Pending captures fail explicitly if their geometry changes. API34+ uses captured-content resize callbacks; older full-screen projection uses display change notifications.

Initial and post-action Host captures share one acquisition contract. Frames include acquisition clocks, frame_id, geometry and acquisition/copy/encoding durations. Android consent and session ownership remain unchanged. Optional expected display dimensions/rotation are checked at UI injection, including Resident Accessibility Host proxy.

Cloud compilation and device rotation checks are still pending. Device validation must cover native app and game portrait/landscape, reverse landscape, fold/unfold and stop during capture. App-window capture with an unknown screen offset reports no certified touch mapping. Newest frame does not guarantee settled animation.
