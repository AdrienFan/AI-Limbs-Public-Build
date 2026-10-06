#!/usr/bin/env python3
"""Ubuntu-owned DIRECT launch policy; Android networking remains Host-owned."""

import json
import os
from pathlib import Path
import shlex
import sys
import time

VERSION = "0.1.0"
SNAPSHOT = Path("/root/laner/tools/laner-net/host-listeners.json")
LIBRARY = Path("/usr/local/lib/ai-limbs/libproxychains4.so")
CONFIG = Path("/root/laner/tools/laner-net/direct-proxychains.conf")
MAX_AGE_MS = 120_000
PROXY_KEYS = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
              "http_proxy", "https_proxy", "all_proxy", "no_proxy")


def direct_endpoint():
    data = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    age = int(time.time() * 1000) - int(data["updated_at_epoch_ms"])
    if not 0 <= age <= MAX_AGE_MS:
        raise ValueError("Host network snapshot is stale")
    proxy = data["direct_proxy"]
    if not (proxy["available"] and proxy["validated"] and proxy["network_not_vpn"]):
        raise ValueError("Host has no validated physical DIRECT network")
    if proxy["host"] != "127.0.0.1" or proxy["scheme"] != "socks5h":
        raise ValueError("Unexpected Host DIRECT endpoint")
    port = int(proxy["port"])
    if not 1 <= port <= 65535:
        raise ValueError("Invalid Host DIRECT port")
    return proxy, port


def routing_config(port):
    # One strict Host-owned route. Do not select or retry a VPN proxy here.
    return ("strict_chain\nquiet_mode\nproxy_dns\nremote_dns_subnet 224\n"
            "tcp_read_time_out 15000\ntcp_connect_time_out 12000\n"
            "localnet 127.0.0.0/255.0.0.0\nlocalnet ::1/128\n"
            f"[ProxyList]\nsocks5 127.0.0.1 {port}\n")


def direct_environment():
    try:
        proxy, port = direct_endpoint()
        reason = ""
    except (OSError, ValueError, KeyError, TypeError) as error:
        # Deny network access without preventing local shell/file maintenance.
        # Port 1 is not a route to a destination; the strict SOCKS handshake fails.
        port = 1
        reason = str(error)
        print(f"laner-direct: DIRECT unavailable: {reason}; TCP route denied", file=sys.stderr)
    if not LIBRARY.is_file():
        raise ValueError("Ubuntu DIRECT routing library is missing")
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    # Atomic replacement preserves complete configuration for concurrent children.
    temporary = CONFIG.with_name(f".direct-{os.getpid()}.tmp")
    temporary.write_text(routing_config(port), encoding="utf-8")
    temporary.replace(CONFIG)
    env = os.environ.copy()
    for key in PROXY_KEYS:
        env.pop(key, None)
    existing = env.get("LD_PRELOAD", "").replace(":", " ").split()
    library = str(LIBRARY)
    env["LD_PRELOAD"] = " ".join([library] + [x for x in existing if x != library])
    env["PROXYCHAINS_CONF_FILE"] = str(CONFIG)
    env["LANER_EGRESS_MODE"] = "DIRECT" if not reason else "DIRECT_UNAVAILABLE"
    env["PROXYCHAINS_QUIET_MODE"] = "1"
    env["LANER_DIRECT_PROXY"] = f"socks5h://127.0.0.1:{port}"
    # Loopback RPC and explicit phone VPN proxies stay reachable locally.
    env["NO_PROXY"] = env["no_proxy"] = "127.0.0.1,localhost,::1"
    return env


def main(argv):
    if argv == ["status"]:
        proxy, port = direct_endpoint()
        print(json.dumps({"mode": "DIRECT", "endpoint": f"socks5h://127.0.0.1:{port}",
                          "transport": proxy["transport"], "validated": proxy["validated"],
                          "routing_library_present": LIBRARY.is_file(),
                          "scope": "dynamically linked TCP clients; explicit laner-net uses VPN",
                          "bypass_verified": False}))
        return 0
    if argv == ["env"]:
        env = direct_environment()
        print("unset " + " ".join(PROXY_KEYS))
        for key in ("LD_PRELOAD", "PROXYCHAINS_CONF_FILE", "LANER_EGRESS_MODE",
                    "LANER_DIRECT_PROXY", "PROXYCHAINS_QUIET_MODE", "NO_PROXY", "no_proxy"):
            print(f"export {key}={shlex.quote(env[key])}")
        return 0
    if not argv or argv[0] != "run" or len(argv) < 2:
        print("Usage: laner-direct status | env | run <command> [args...]", file=sys.stderr)
        return 2
    command = argv[1:]
    os.execvpe(command[0], command, direct_environment())


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"laner-direct: {error}; command was not launched through VPN", file=sys.stderr)
        sys.exit(3)
