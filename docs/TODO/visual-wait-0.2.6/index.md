---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build
status: implementation complete; cloud verification pending
---

# Visual change and stability observation 0.2.6

The build112 device test showed that a successful tap returned a fresh frame while Settings was still loading. Add bounded pixel observation to the generic Visual Manager, using the existing host.screen.session@1 frame primitives. This iteration does not change Base or any bridge.

Scope: Visual Manager controller, capability declarations, pure RGB detector tests, version 0.2.6 and interface documentation. Keep tap_on_frame's existing new_frame default for deployed consumers. Add opt-in stable and change_then_stable modes, and standalone wait_for_visual_change / wait_until_stable capabilities. No new session, permission request, action replay, or alternate capture route belongs in this workflow.

## Interface

Both waits require the latest screen frame_id from get_frame/frame/action feedback. They use that frame's session, geometry and uncompressed RGB baseline. tap_on_frame accepts observe_mode=new_frame/stable/change_then_stable, with new_frame as the compatible default.

Common optional parameters are timeout_ms=5000, stable_ms=250, sample_interval_ms=100, change_ratio=0.02, stable_ratio=0, pixel_tolerance=12 and max_edge=1024. Stability defaults to no changed sample points beyond the channel tolerance, so small sampled loading animations are not deliberately ignored. Callers may explicitly raise stable_ratio to tolerate small animations. Wait budget is 100..15000 ms; stable windows are 100..5000 ms and must fit the budget in stable modes. Internal capture waits use the remaining budget. Transport and final preview encoding add time outside the detection budget.

Normalized region_left/top default to 0; region_width/height default to 1. A region must fit inside the frame and each dimension must be at least 1/64. Comparisons select points inside this region from a 64×64 RGB grid. This can exclude clocks, moving game units and other animation. The grid is a bounded heuristic, not exhaustive pixel comparison; tiny visual changes between sample points may be missed.

Pixels are sampled before JPEG encoding and independently of preview size. A sample changes when any RGB channel exceeds pixel_tolerance. Ratios are the fraction of selected grid points that changed. Change waits compare with the original baseline. Stability compares adjacent frames and a fixed quiet-window anchor, so slow cumulative drift resets the window. Different producer frame IDs and monotonic capture times are required; repeated cached frames cannot prove stability. A static source that emits no additional frame can time out.

Internally, the first capture uses new_surface and subsequent bounded polls use latest. Intermediate frames are not preview-encoded or uploaded. Only the final frame becomes an image attachment; scratch files are released on success, timeout, failure or cancellation. Session stop invalidates the pending operation; geometry changes are errors.

## Result semantics

success=true means the capability returned a result, not that the waiting condition was met. observation_success=true means an image was obtained. wait_success and visual_wait.condition_met indicate whether the requested condition was satisfied. visual_wait.status is READY or TIMEOUT; TIMEOUT can include the last observed frame but must never be described as stable. visual_wait includes elapsed_ms, distinct samples, polls, change ratios, stable_elapsed_ms, thresholds and region.

For a tap, action_success remains true if the injection completed even when observation fails or times out. automatic_reexecution=false prevents a waiting result from authorizing another tap. Actual Host failures remain explicit failures; no stale-image replacement or action retry is performed. Existing operation.feedback still returns a single fresh frame; clients wanting stability use the new capabilities or tap_on_frame options.

Pixel stability describes sampled rendered content. It cannot prove that network requests, application loading or game logic finished. A frozen loading page can look stable. For semantic completion, combine the final image with an application-specific condition. change_then_stable sees only sampled frames after action completion, so a transient change that finished earlier may be missed; use stable for a resulting page where post-action change is not required.

## Validation

Cloud workflow runs capability/manifest consistency, source provenance, unit tests, payload compilation and signed packaging. New tests cover continuous animation, change-then-stability, no change, duplicate frames, accumulated drift, RGB-only changes, ROI inclusion/exclusion, threshold boundaries, monotonic clocks, invalid options and packed RGBA sampling. Controller tests check that invalid settings and missing baselines cannot invoke Host or inject a tap.

Device remains locked during development. This version has not yet received an installed-device wait/animation regression test; perform that after deployment.

[DONE] Implementation and tests prepared; cloud result recorded separately by build run.
