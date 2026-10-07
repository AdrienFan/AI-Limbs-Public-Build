# Host frame substrate for planned visual optimizations

Scope: additive operations within host.screen.session@1 and host.screen.capture@1. Host supplies pixels, geometry, arrival events, input and lifecycle. The plugin owns visual change thresholds, regions, scene preconditions, stabilization and image encoding policy. Bridges own delivery.

## Operations

- host.screen.session@1/start, status, stop: existing owner-bound session lifecycle. status now includes capture activity, latest frame identity/age, geometry, visibility where reported by Android and producer errors.
- host.screen.session@1/frame: session_id required; frame_format png or rgba8888. frame_mode=new_surface retains a fresh empty-surface acquisition; frame_mode=latest copies the continuously drained latest image. latest takes max_age_ms from0 to60000, timeout_ms from1 to15000, and optional after_frame_id from the same producer. Default output remains PNG; default mode remains new_surface.
- host.screen.session@1/wait_frame: ready owned session_id and after_frame_id required; waits for a newer latest-frame sequence. It never opens sharing or requests consent. Timeout is an execution bound, not an authorization lifetime. No silently returned cached/older frame on timeout.
- host.screen.session@1/geometry: owned session_id required; reads pipeline state and current capture/display geometry without acquiring/encoding an image.
- host.screen.capture@1/capture_frame: standalone enriched capture for one-shot plugin observation and image archives. The older capture operation is preserved unchanged.

Each enriched frame includes frame_id, captured_at_ms, Android elapsed acquisition clock, pixel dimensions/format/strides, geometry_id, display_id, rotation in degrees, content_rect and physical touch dimensions. touch_mapping_available is false when a captured app window has unknown screen placement. Raw RGBA is packed with row_stride=width*4 and pixel_stride=4. PNG archives remain lossless.

## Input preconditions and context

host.ui.automation@1 tap/long_press/swipe may include expected_display_width, expected_display_height, expected_display_rotation and expected_geometry_id. Host and Resident injection paths validate them. Native UI actions without these optional fields keep their previous behavior. A geometry ID also detects a rotate-away-and-back epoch while the old pixel dimensions match again.

Operation feedback includes action identity, completion clocks and allowlisted spatial/control parameters. Input text and shell command bodies are not copied into visual history. The existing screen_feedback=false permits a plugin to orchestrate actions and its own observation policy without duplicate native screenshots. No action replay is introduced.

## Coverage of the eight items

1. Rotation/capture geometry: captured-content resize and display rotation notifications.
2. Coordinate mapping: raw frame geometry and optional injection preconditions; image/normalized conversion belongs to the plugin.
3. Clean frames/high-resolution crops: enriched frame/capture_frame; crop and encoding belong to the plugin.
4. Latency: frame wait, copy, encoding, age and existing bridge segments.
5. Visual-change/stability waits: latest/new_surface plus wait_frame and acquisition clocks; pixel comparison belongs to the plugin.
6. Batch scene interruptions: per-step UI actions, screen_feedback=false and frame waits; scene comparison/stop policy belongs to the plugin.
7. Continuous sessions: ImageReader drains continuously and holds one newest Image; latest reads reuse that Surface. Fresh post-action reads deliberately use a new Surface for their existing freshness guarantee.
8. UI/vision strategy and history: existing UI snapshot plus frame pixels and generic action context. No game recognition or specific plugin identity enters Host.

## Explicit limits

latest_buffer clocks describe acquisition by Host, not proof of post-action rendering; use fresh=true/new_surface for that existing freshness contract. Static screens may produce no new image, so wait_frame can time out. Fresh images do not mean animations have settled. Camera sessions retain their existing frame/configuration primitives. This does not supply an OpenAI live-video input connection.

Cloud validation and device installation remain pending. Device checks must include native apps, games, reverse landscape, folding, stop/cancellation, latest frame reuse, sequence waits and stale geometry rejection.
