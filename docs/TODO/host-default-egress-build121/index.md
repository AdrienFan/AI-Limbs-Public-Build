# Host default egress — build121

- Add host.network@1/egress_proxy with explicit SYSTEM or DIRECT routes.
- Keep direct_proxy compatible; SYSTEM sockets and DNS execute in Android Host.
- Use operation-level HOST_SERVICE affinity, including Resident Kernel proxy routing.
- Keep all subsystem settings and UI in the owning child extension.
- Cloud build stable and comparison APKs; comparison identity is build121.
