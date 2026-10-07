# Universal visual frames and frame-bound taps

Fork: https://github.com/AdrienFan/AI-Limbs-Public-Build

Visual Manager0.2.5 requires build112 for enriched screen frames. Existing frame/start/capture interfaces remain; get_frame and tap_on_frame are additive. Both are plugin capabilities available through the shared catalog to any authorized bridge.

Explicit screen frames use raw RGBA acquisition and one preview JPEG encode. frame save=true retains lossless PNG originals. Frame metadata retains original dimensions separately from preview dimensions; image_to_touch is recalculated for resized previews. Non-full-display capture has no guessed mapping. Normalized endpoints map into 0..width-1 and 0..height-1.

Images travel through mcp_content only, with no duplicate Base64 in image metadata. Tap outcomes separate action_success from observation_success; failed observation never replays a tap. Latest frame/session and display geometry are checked before injection. This is not continuous model video or a semantic completion guarantee.

Cloud checks include capability/manifest alignment, frame-coordinate boundary tests and the existing regression suite. Device rotation and touch accuracy remain pending installation. Follow-up work: ROI visual-change waits, batch scene guards, latest-frame cache and clearer UI-tree applicability.
