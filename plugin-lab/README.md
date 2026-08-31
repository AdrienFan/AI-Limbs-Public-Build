# AI Limbs Plugin Lab

This branch keeps only lifecycle, package verification, permission brokering, capability routing,
extension routing, storage isolation, and the Plugin Center in the installed base.

Optional behavior is delivered as deterministic `.ailp` packages:

- `headless-echo`: registers a headless `plugin.*` capability.
- `runtime-log-viewer`: requests `host.logs.read`, registers a proxied capability, and installs
  both a home tile and a declarative screen.

Run `python3 tools/plugin_lab/build_samples.py` after changing a sample. This packages JSON and
resources only; it does not compile the Android project.
