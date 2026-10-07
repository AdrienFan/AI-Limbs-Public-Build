---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
component: visual-manager
version: 0.2.4
---

# Encode screen feedback once

The visual plugin explicitly requests packed RGBA8888 fresh session frames from
build111. It creates the final resized JPEG once, saves those same bytes and builds
MCP content from them. There is no full-resolution PNG encoding/decoding or Base64
decode-to-file in this screen feedback path. Ownership, deadlines, freshness,
operation identity, generation checks and error semantics remain.

Use a 960-pixel maximum edge for automatic screen feedback, keeping JPEG quality
82. A stored text UI comparison returned 72,141 bytes at 960 vs 84,207 at 1024 and
remained readable in that sample. This does not prove every small text remains
readable. Normal frame defaults stay at 1024; frame accepts max_edge up to 2048 for
explicit detail reads. Raw frames are bounded at 64 MiB and must exactly match
packed layout metadata. Camera, archival and legacy PNG single-frame paths remain
separate declared representations. Unsupported raw requests fail explicitly.

Cloud validation covers raw metadata errors and existing feedback contracts; no
local Gradle commands run. Full encoding, memory and readability validation needs
deployment of the matching base/plugin pair.
