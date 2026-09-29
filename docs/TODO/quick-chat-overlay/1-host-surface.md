# Host overlay surface [DONE]

Bind `host.window.overlay@1` to Android Host with `create/update/remove/list`.
Only a mounted presentation that explicitly declares `overlay_enabled=true` can be rendered.
The Host owns WindowManager, permission, focus and a movable title bar.

A presentation overlay may declare `host_collapsed_drag=true`. For its collapsed window,
Host intercepts a drag after touch slop and moves the same WindowManager window locally;
taps still reach the plugin-owned content. No per-move capability call is needed.
