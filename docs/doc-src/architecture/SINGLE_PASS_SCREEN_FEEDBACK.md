# Single-pass screen feedback in build111

The Host exposes representation and lifecycle primitives. The visual plugin owns image codec, quality and resize decisions. The bridge owns sequencing decisions. Every native action still enters the normal dispatcher and permission system.

## Fresh screen frame representation

An owned, already ready host.screen.session@1 session accepts frame with fresh=true and frame_format=rgba8888. No projection is started by this read. The fresh-surface acquisition, ownership, deadline and consent checks remain in place.

The nested frame includes path, width, height, format=rgba8888, pixel_stride=4, row_stride=width*4 and byte_count=width*height*4. It is a tightly packed raw RGBA8888 file with no row padding and MIME application/octet-stream. It is local transport between Host and plugin, never an MCP image attachment. The plugin validates the metadata and file length before loading pixels, encodes one JPEG and deletes the raw scratch file.

Omitting frame_format explicitly retains the published PNG representation. Invalid representations fail. Raw output requires fresh=true. Host does not choose JPEG quality, image size or action batching.

## Deferring operation feedback

Native screen actions accept optional boolean screen_feedback, default true. Setting it false suppresses only the sideband image; action authorization and execution are unchanged. execute_shell also requires screen_action=true to request screen feedback. Both controls are validated and removed before native argument dispatch.

ai_limbs.operation_feedback.read reads the currently registered, already active feedback Providers without executing or replaying an action. It never starts sharing or requests MediaProjection consent. It preserves the usual freshness, six-second deadline and explicit INACTIVE or FAILED responses.

Bridge 0.0.29 validates and resolves 1..8 known UI actions before execution, invokes each with screen_feedback=false and reads final feedback once. A failed or uncertain step stops the sequence. Deadlines cancel further work. Earlier effects remain; there is no automatic replay or rollback.

## Matched release and validation

Install base 0.8.0.17-build111, visual-manager 0.2.4 and ChatGPT bridge 0.0.29 together. Refresh bridge MCP metadata after installation to expose ai_limbs_capability_batch.

The visual plugin defaults post-action feedback to JPEG quality 82 and longest edge 960, preserves aspect ratio and does not upscale. Its explicit frame max_edge parameter allows up to 2048 for inspecting small text. Shrinking necessarily removes pixels; readability must be checked on deployed app screens.

Cloud regression coverage checks padded raw rows, truncated buffers, exact raw metadata, feedback deferral, preflight failures, sequence stopping, cancellation, lost results and duplicate delivery. Device encoding time, end-to-end latency and text readability remain to be measured.
