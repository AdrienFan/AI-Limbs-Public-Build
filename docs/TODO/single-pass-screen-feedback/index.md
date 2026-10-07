---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
component: base
version: 0.8.0.17-build111
---

# Single-pass screen feedback primitives

Provide optional packed RGBA8888 output for fresh owned screen session frames.
Keep PNG as the existing default representation. No codec, resize, quality or
batch orchestration policy moves into Host. The visual plugin requests raw pixels
and encodes its final preview once. Raw planes are validated and row padding is
excluded. Ownership, post-action freshness, deadlines and Android consent remain.

Add a generic boolean screen_feedback control to native invocations and a read-only
ai_limbs.operation_feedback.read entry for already active Providers. The bridge
owns sequential batch policy; every step retains normal Host authorization. The
read never starts sharing or replays operations. New clients explicitly request
these primitives; missing support reports an error.

Validate packed rows, malformed layouts and feedback control in cloud unit tests.
Use the matched visual 0.2.4 and bridge 0.0.29 releases. Device performance and
readability remain unverified until deployment.
