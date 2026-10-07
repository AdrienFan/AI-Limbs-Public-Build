---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
component: chatgpt-native-probe
version: 0.0.28
---

# Attribute slow response POSTs

## Device evidence from 0.0.27

Controlled HOME calls returned in 1.3–1.7 s without screen feedback and 4.1–5.0 s
with feedback. Host invocation with image took 1.83–1.94 s, adapter 57–98 ms, and
response POST 1.08–1.89 s. Images were about 87 KB; actual frame acquisition was
54–60 ms and excludes bitmap conversion and PNG encoding. A stored 6.7 KB preview
sometimes returned slower than an 87 KB preview.

A start_app call returning to ChatGPT took 10.809 s at the tool boundary. Its local
phone-to-ACK span was 7.470 s: Host 1.521 s, adapter 0.133 s and response POST
5.682 s. These boundaries do not attribute time outside the phone span. First
screen start also failed after 11.539 s inside Host; once projection readiness was
observed, a second start succeeded. That startup failure is not an ordinary action
feedback sample.

## Scope and intent

Observe response POSTs through request-local OkHttp EventListener hooks. Preserve
the original shared connection pool, dispatcher, routes, TLS policy, deadlines and
retry behavior. Retain aggregate numeric costs only. Separate observed DNS,
connection/TLS, body writes and waiting through response header completion. Clear
old attempt-only fields when the gateway starts a new delivery attempt.

Do not label responseHeadersStart as first-byte arrival: OkHttp raises it before
blocking for response headers. Do not label header wait as pure remote processing.
Do not add overlapping connection setup fields together. Document cached connection
omissions and partial failed phase timings.

## Verification

Add deterministic event-sequence regressions and an engine-to-status test using the
existing local MockWebServer fixture in the cloud suite. Cover retry replacement,
failed phases, reused connections and privacy. No local Gradle commands run. Trigger
only the existing chatgpt-native-probe cloud test/build/package target.

[DONE] Implementation and regression definitions; cloud results and deployed-device
verification are reported separately.
