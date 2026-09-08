#!/usr/bin/env python3
"""laner-net: opt-in dynamic proxy wrapper for AI Limbs Ubuntu commands."""

from __future__ import annotations

import concurrent.futures
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

VERSION = "0.2.0"
CONFIG_DIR = Path("/root/laner/tools/laner-net")
CONFIG_FILE = CONFIG_DIR / "config.json"
HOST_LISTENERS_FILE = CONFIG_DIR / "host-listeners.json"
DEFAULT_PROBE_URL = "https://api.github.com"
SCAN_HOST = "127.0.0.1"
HOST_HINT_MAX_AGE_MS = 120_000
PROXY_CONNECT_TIMEOUT = 1
PROXY_MAX_TIME = 2
LOCAL_BYPASS = ("127.0.0.1", "localhost", "::1")
PROXY_ENV_KEYS = (
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    "http_proxy", "https_proxy", "all_proxy", "no_proxy",
)


def default_config() -> dict:
    return {
        "proxy": None,
        "probe_url": DEFAULT_PROBE_URL,
        "auto_detect": True,
        "scan_host": SCAN_HOST,
    }


def load_config() -> dict:
    config = default_config()
    if CONFIG_FILE.is_file():
        try:
            data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                config.update(data)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"laner-net: ignoring invalid config: {exc}", file=sys.stderr)
    return config


def save_config(config: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(CONFIG_FILE)


def parse_proxy(proxy: str):
    parsed = urlparse(proxy)
    if parsed.scheme not in {"http", "https", "socks5", "socks5h"}:
        raise ValueError("proxy scheme must be http, https, socks5, or socks5h")
    if not parsed.hostname or not parsed.port:
        raise ValueError("proxy must include host and port")
    return parsed


def tcp_open(host: str, port: int, timeout: float = 0.20) -> bool:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            return sock.connect_ex((host, port)) == 0
    except OSError:
        return False


def clean_proxy_env(env: dict | None = None) -> dict:
    result = (env or os.environ).copy()
    for key in PROXY_ENV_KEYS:
        result.pop(key, None)
    return result


def curl_probe(
    proxy: str | None,
    url: str,
    timeout: int = PROXY_MAX_TIME,
    connect_timeout: int = PROXY_CONNECT_TIMEOUT,
) -> tuple[bool, str]:
    curl = shutil.which("curl")
    if not curl:
        return False, "curl_not_found"
    cmd = [
        curl, "-L", "-sS", "-o", "/dev/null",
        "-w", "%{http_code} %{time_total}",
        "--connect-timeout", str(connect_timeout),
        "--max-time", str(timeout),
    ]
    if proxy:
        cmd += ["--proxy", proxy]
    cmd.append(url)
    proc = subprocess.run(cmd, text=True, capture_output=True, env=clean_proxy_env())
    output = (proc.stdout + " " + proc.stderr).strip().replace("\n", " | ")
    if proc.returncode != 0:
        return False, output or f"curl_rc_{proc.returncode}"
    code = proc.stdout.strip().split(" ", 1)[0]
    return code.isdigit() and code != "000", output


def proxy_probe(proxy: str | None, url: str) -> tuple[bool, str]:
    if not proxy:
        return False, "proxy_missing"
    try:
        parsed = parse_proxy(proxy)
    except ValueError as exc:
        return False, f"invalid_proxy:{exc}"
    if not tcp_open(parsed.hostname, parsed.port):
        return False, "tcp_closed"
    return curl_probe(proxy, url)


def load_host_listener_ports() -> tuple[list[int], list[str]]:
    notes: list[str] = []
    if not HOST_LISTENERS_FILE.is_file():
        return [], ["host_listener_snapshot:missing"]
    try:
        data = json.loads(HOST_LISTENERS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [], [f"host_listener_snapshot:invalid:{exc}"]
    if not isinstance(data, dict):
        return [], ["host_listener_snapshot:invalid_root"]

    updated_at = int(data.get("updated_at_epoch_ms") or 0)
    now_ms = int(time.time() * 1000)
    age_ms = max(0, now_ms - updated_at) if updated_at > 0 else HOST_HINT_MAX_AGE_MS + 1
    if age_ms > HOST_HINT_MAX_AGE_MS:
        return [], [f"host_listener_snapshot:stale:{age_ms}ms"]
    if not bool(data.get("available", False)):
        reason = str(data.get("reason") or "unavailable").replace("\n", " ")
        return [], [f"host_listener_snapshot:unavailable:{reason}"]

    ports: set[int] = set()
    raw_ports = data.get("ports")
    if isinstance(raw_ports, list):
        for raw in raw_ports:
            try:
                port = int(raw)
            except (TypeError, ValueError):
                continue
            if 1 <= port <= 65535:
                ports.add(port)
    result = sorted(ports)
    notes.append(f"host_listener_snapshot:fresh:{age_ms}ms:ports={len(result)}")
    return result, notes


def probe_open_ports(
    host: str,
    ports: list[int],
    probe_url: str,
    preferred_scheme: str | None = None,
) -> tuple[str | None, list[str]]:
    notes: list[str] = []
    if not ports:
        return None, notes

    schemes: list[str] = []
    if preferred_scheme in {"http", "https", "socks5", "socks5h"}:
        schemes.append(preferred_scheme)
    for scheme in ("http", "socks5h"):
        if scheme not in schemes:
            schemes.append(scheme)

    def try_port(port: int) -> tuple[str | None, list[str]]:
        local_notes: list[str] = []
        for scheme in schemes:
            proxy = f"{scheme}://{host}:{port}"
            ok, detail = curl_probe(proxy, probe_url)
            local_notes.append(f"probe:{proxy}:{'ok' if ok else 'fail'}:{detail}")
            if ok:
                return proxy, local_notes
        return None, local_notes

    workers = min(12, len(ports))
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(try_port, port): port for port in ports}
        for future in concurrent.futures.as_completed(futures):
            proxy, local_notes = future.result()
            notes.extend(local_notes)
            if proxy:
                for other in futures:
                    if other is not future:
                        other.cancel()
                return proxy, notes
    return None, notes


def detect_proxy(
    probe_url: str,
    host: str = SCAN_HOST,
    preferred_scheme: str | None = None,
) -> tuple[str | None, list[str]]:
    ports, notes = load_host_listener_ports()
    if not ports:
        return None, notes
    reachable = [port for port in ports if tcp_open(host, port)]
    notes.append("host_listener_reachable:" + (",".join(map(str, reachable)) or "none"))
    proxy, probe_notes = probe_open_ports(host, reachable, probe_url, preferred_scheme)
    notes.extend(probe_notes)
    return proxy, notes


def ensure_proxy(config: dict, force_detect: bool = False) -> tuple[str | None, str, list[str]]:
    notes: list[str] = []
    cached = config.get("proxy")
    probe_url = str(config.get("probe_url") or DEFAULT_PROBE_URL)
    preferred_scheme: str | None = None

    if cached:
        try:
            preferred_scheme = parse_proxy(cached).scheme
        except ValueError as exc:
            notes.append(f"cached_proxy_invalid:{exc}")
            cached = None

    if not force_detect and cached:
        ok, detail = proxy_probe(cached, probe_url)
        if ok:
            return cached, "cached", notes
        notes.append(f"cached_proxy_failed:{cached}:{detail}")

    if not config.get("auto_detect", True) and not force_detect:
        return None, "disabled", notes

    host = str(config.get("scan_host") or SCAN_HOST)
    proxy, detect_notes = detect_proxy(probe_url, host, preferred_scheme)
    notes.extend(detect_notes)
    if proxy:
        config["proxy"] = proxy
        config["scan_host"] = host
        config["last_detection"] = {
            "source": "host-listeners",
            "proxy": proxy,
            "at_epoch_ms": int(time.time() * 1000),
        }
        save_config(config)
        return proxy, "host-listeners", notes
    return None, "not-found", notes


def merge_no_proxy(env: dict) -> str:
    existing = env.get("NO_PROXY") or env.get("no_proxy") or ""
    items = [x.strip() for x in existing.split(",") if x.strip()]
    for item in LOCAL_BYPASS:
        if item not in items:
            items.append(item)
    return ",".join(items)


def proxy_environment(proxy: str) -> dict:
    original = os.environ.copy()
    no_proxy = merge_no_proxy(original)
    env = clean_proxy_env(original)
    parsed = parse_proxy(proxy)
    if parsed.scheme.startswith("socks"):
        env["ALL_PROXY"] = env["all_proxy"] = proxy
    else:
        env["HTTP_PROXY"] = env["http_proxy"] = proxy
        env["HTTPS_PROXY"] = env["https_proxy"] = proxy
        env["ALL_PROXY"] = env["all_proxy"] = proxy
    env["NO_PROXY"] = env["no_proxy"] = no_proxy
    env["LANER_NET_ACTIVE"] = "1"
    env["LANER_NET_PROXY"] = proxy
    return env


def run_command(command: list[str]) -> int:
    if not command:
        print("laner-net: missing command", file=sys.stderr)
        return 2
    config = load_config()
    proxy, source, notes = ensure_proxy(config)
    if not proxy:
        print("laner-net: no working phone-side proxy found", file=sys.stderr)
        for note in notes:
            print(f"laner-net: {note}", file=sys.stderr)
        return 3
    if source == "host-listeners":
        print(f"laner-net: host snapshot selected {proxy}", file=sys.stderr)
    env = proxy_environment(proxy)
    os.execvpe(command[0], command, env)
    return 127


def show_status() -> int:
    config = load_config()
    cached = config.get("proxy")
    hint_ports, hint_notes = load_host_listener_ports()
    print(f"laner-net {VERSION}")
    print(f"config: {CONFIG_FILE}")
    print(f"host_listener_file: {HOST_LISTENERS_FILE}")
    print(f"host_listener_candidates: {','.join(map(str, hint_ports)) or 'none'}")
    for note in hint_notes:
        print(note)
    print(f"cached_proxy: {cached or 'none'}")
    proxy, source, notes = ensure_proxy(config)
    print(f"resolved_proxy: {proxy or 'none'}")
    print(f"proxy_source: {source}")
    if proxy:
        ok, detail = proxy_probe(proxy, str(config.get("probe_url") or DEFAULT_PROBE_URL))
        print(f"proxy_probe: {'ok' if ok else 'fail'} ({detail})")
    else:
        for note in notes:
            if note not in hint_notes:
                print(note)
    print("global_environment_modified: no")
    return 0 if proxy else 4


def command_detect() -> int:
    config = load_config()
    proxy, source, notes = ensure_proxy(config, force_detect=True)
    for note in notes:
        print(note)
    if not proxy:
        print("laner-net: no working phone-side proxy found", file=sys.stderr)
        return 4
    print(f"selected_proxy: {proxy}")
    print(f"source: {source}")
    return 0


def command_set(proxy: str) -> int:
    try:
        parse_proxy(proxy)
    except ValueError as exc:
        print(f"laner-net: {exc}", file=sys.stderr)
        return 2
    config = load_config()
    config["proxy"] = proxy
    save_config(config)
    ok, detail = proxy_probe(proxy, str(config.get("probe_url") or DEFAULT_PROBE_URL))
    print(f"proxy_cached: {proxy}")
    print(f"proxy_probe: {'ok' if ok else 'fail'} ({detail})")
    print("note: cached proxy is only a last-known value and will be replaced from Host listener hints when it stops working")
    return 0


def command_test(url: str | None = None) -> int:
    config = load_config()
    target = url or str(config.get("probe_url") or DEFAULT_PROBE_URL)
    direct_ok, direct_detail = curl_probe(None, target)
    proxy, source, notes = ensure_proxy(config)
    print(f"target: {target}")
    print(f"direct: {'ok' if direct_ok else 'fail'} ({direct_detail})")
    if not proxy:
        print("proxy: not found")
        for note in notes:
            print(note)
        return 5
    proxy_ok, proxy_detail = curl_probe(proxy, target)
    print(f"resolved_proxy: {proxy} ({source})")
    print(f"proxy: {'ok' if proxy_ok else 'fail'} ({proxy_detail})")
    return 0 if proxy_ok else 5


def command_env() -> int:
    config = load_config()
    proxy, source, notes = ensure_proxy(config)
    if not proxy:
        print("laner-net: no working phone-side proxy found", file=sys.stderr)
        for note in notes:
            print(f"laner-net: {note}", file=sys.stderr)
        return 4
    env = proxy_environment(proxy)
    print("unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy")
    keys = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "LANER_NET_ACTIVE", "LANER_NET_PROXY")
    for key in keys:
        if key in env:
            print(f"export {key}={shlex.quote(env[key])}")
    return 0


def command_shell() -> int:
    shell = os.environ.get("SHELL") or "/bin/bash"
    return run_command([shell])


def print_help() -> None:
    print(f"""laner-net {VERSION}
Usage:
  laner-net status
  laner-net detect
  laner-net set <proxy-url>
  laner-net test [url]
  laner-net env
  laner-net shell
  laner-net run <command> [args...]
  laner-net <command> [args...]

Behavior:
  The saved proxy is only a last-known cache.
  AI Limbs Host publishes a fresh read-only Android TCP listener snapshot.
  If the cached proxy fails, laner-net tests only those current listener ports,
  validates HTTP/SOCKS, updates the cache, and runs the requested command.
  It never brute-force scans the 1-65535 TCP port space.

Examples:
  laner-net curl https://www.google.com/
  laner-net git ls-remote https://github.com/git/git.git HEAD
  laner-net gh api /rate_limit

Only commands launched through laner-net receive proxy variables.
The global Ubuntu network configuration is never changed.""")


def main(argv: list[str]) -> int:
    if not argv or argv[0] in {"-h", "--help", "help"}:
        print_help()
        return 0
    if argv[0] in {"-V", "--version", "version"}:
        print(VERSION)
        return 0
    command = argv[0]
    if command == "status":
        return show_status()
    if command in {"detect", "refresh"}:
        return command_detect()
    if command == "set":
        if len(argv) != 2:
            print("usage: laner-net set <proxy-url>", file=sys.stderr)
            return 2
        return command_set(argv[1])
    if command == "test":
        return command_test(argv[1] if len(argv) > 1 else None)
    if command == "env":
        return command_env()
    if command == "shell":
        return command_shell()
    if command == "run":
        return run_command(argv[1:])
    if command == "--":
        return run_command(argv[1:])
    return run_command(argv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
