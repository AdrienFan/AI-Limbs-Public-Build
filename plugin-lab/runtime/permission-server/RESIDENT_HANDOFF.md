# Resident lifetime extension (source candidate)

This private protocol pairs with the Base feat/resident-runtime-build26 branch. No build or device validation has been performed. The installed permission payload does not gain this extension until a new paired release is built and installed. Existing Shizuku transactions and the verified Host UID restriction remain unchanged.

Binder transaction 0x41494c uses descriptor ai_limbs.permission.resident.v1, protocol integer 1, then operation: 0 describe, 1 claim, 2 release, 3 prepare. Mutations include launch token, Core session and Core-owned lifetime Binder; release keeps the legacy destination field for wire compatibility; both 0 and 1 now detach Resident ownership and return the existing backend to Host without stopping the permission daemon. Replies contain an exception header followed by JSON with server instance, server PID/UID and Core ownership identity.

Prepare links Core death and suppresses periodic Host Provider handoff. Claim requires the same prepared PID/session/Binder. Core death keeps the backend alive and schedules Host handoff. Explicit release unlinks death and also schedules Host handoff; Resident lifecycle never terminates the permission daemon. Old in-flight Host results are applied only while no Core lease exists. Provider calls run on a single worker so they do not block the server main Looper.

This is a lifetime handoff, not a wake lock or a freeze exemption. Base has not connected its Resident switch or business kernel to ownership activation yet. Remaining acceptance includes wrong sessions/PIDs/tokens, unknown operations, duplicate preparations, death during transfer, delayed Host responses, release and user stop. Version numbers remain unchanged until coordinated release preparation.
