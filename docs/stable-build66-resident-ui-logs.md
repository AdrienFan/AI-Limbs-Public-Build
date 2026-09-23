# Stable build66: Resident UI recovery and plugin logs

Build66 is based directly on stable build65 and integrates the two Resident fixes that were already completed but omitted from the build65 integration:

- Keep the Resident plugin area in an explicit connecting/loading state until the UI proxy reports restoration ready, instead of showing an empty plugin area during recovery.
- Bind the Worker file logger context and record plugin/child-extension lifecycle outcomes under their own log sources so Log Center can read Resident plugin logs from the shared file log.

The stable Android package identity and signing configuration remain unchanged so this build updates the existing stable test base in place and preserves its installed plugin state.
