# LEV Network Mutual Probe

Two new independent Android apps test Samsung Freecess/LEV recovery with a real external HTTP stream plus cross-UID wake broadcasts.

- Net Guardian A: `com.ailimbs.netmutualprobe.guardian`
- Net Target B: `com.ailimbs.netmutualprobe.target`
- Each can independently enable/disable an ntfy JSON stream.
- Each can arm a server-side delayed wake message for +90 seconds.
- Any external stream line immediately sends an explicit PING broadcast to the partner.
- Both retain the 2-second FGS heartbeat and mutual PING/ACK evidence.

Decisive test: whitelist both, start mutual keepalive on both, enable external stream on A only, keep B external stream off, arm A +90s, lock screen for at least 2 minutes, then compare Freecess FZ/UFZ with `NetMutualProbe` logs.
