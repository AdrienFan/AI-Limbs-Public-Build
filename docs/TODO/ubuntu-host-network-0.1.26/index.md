# Ubuntu follow-Host network — 0.1.26

## Contract
- Default remains physical DIRECT for dynamically linked TCP clients.
- Network switch belongs to Ubuntu configuration view, embedded through the existing parent API.
- Saved follow_host intent and active_follow_host runtime state are distinct.
- Saving while running never changes active sockets, route files or child environments.
- Startup latches saved intent and prepares the route before any local shell launches.
- Stop releases runtime state; next start applies the saved setting.
- Host/Resident UI uses the same business state; Resident invokes settings.network.set.
- SYSTEM route requires base build121 host.network@1/egress_proxy; no alternate route is substituted.
- Explicit laner-net remains separate and excludes both Host-owned listener ports.
- UDP and statically linked clients are outside this preload TCP mechanism.

## Validation and delivery
- Review saved-vs-active lifecycle, local/Resident ownership, default DNS/socket affinity.
- Run existing Ubuntu core and child tests in the cloud workflow.
- Cloud-package Ubuntu 0.1.26 and base build121; parent page gets generic scrolling separately.

Parent companion: environment center 0.2.14 fixes the retired UI service dependency and makes the configuration page scrollable.
