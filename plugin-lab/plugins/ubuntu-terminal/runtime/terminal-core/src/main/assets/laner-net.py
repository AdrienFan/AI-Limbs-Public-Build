#!/usr/bin/env python3
"""laner-net: opt-in proxy wrapper for AI Limbs Ubuntu commands."""

from __future__ import annotations

import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

VERSION = "0.1.0"
CONFIG_DIR = Path("/root/laner/tools/laner-net")
CONFIG_FILE = CONFIG_DIR / "config.json"
DEFAULT_PROXY = "http://127.0.0.1:7892"
DEFAULT_PROBE_URL = "https://www.google.com/generate_204"
CANDIDATE_PORTS = (7892, 7890, 7891, 1080, 10808, 10809, 8080, 8888, 3128)
LOCAL_BYPASS = ("127.0.0.1", "localhost", "::1")


def default_config() -> dict:
    return {"proxy": DEFAULT_PROXY, "probe_url": DEFAULT_PROBE_URL}


def load_config() -> dict:
    config = default_config()
    if CONFIG_FILE.is_file():
        config.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
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


def tcp_open(host: str, port: int, timeout: float = 0.35) -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def curl_probe(proxy: str | None, url: str, timeout: int = 10) -> tuple[bool, str]:
    curl = shutil.which("curl")
    if not curl:
        return False, "curl_not_found"
    cmd = [
        curl, "-L", "-sS", "-o", "/dev/null",
        "-w", "%{http_code} %{time_total}",
        "--connect-timeout", "4", "--max-time", str(timeout),
    ]
    if proxy:
        cmd += ["--proxy", proxy]
    cmd.append(url)
    proc = subprocess.run(cmd, text=True, capture_output=True)
    output = (proc.stdout + " " + proc.stderr).strip().replace("\n", " | ")
    if proc.returncode != 0:
        return False, output or f"curl_rc_{proc.returncode}"
    code = proc.stdout.strip().split(" ", 1)[0]
    return code.isdigit() and code != "000", output


def proxy_reachable(proxy: str) -> bool:
    parsed = parse_proxy(proxy)
    return tcp_open(parsed.hostname, parsed.port)


def detect_proxy(probe_url: str) -> tuple[str | None, list[str]]:
    notes: list[str] = []
    for port in CANDIDATE_PORTS:
        if not tcp_open("127.0.0.1", port):
            continue
        notes.append(f"tcp_open:127.0.0.1:{port}")
        for scheme in ("http", "socks5h"):
            proxy = f"{scheme}://127.0.0.1:{port}"
            ok, detail = curl_probe(proxy, probe_url)
            notes.append(f"probe:{proxy}:{'ok' if ok else 'fail'}:{detail}")
            if ok:
                return proxy, notes
    return None, notes


def merge_no_proxy(env: dict) -> str:
    existing = env.get("NO_PROXY") or env.get("no_proxy") or ""
    items = [x.strip() for x in existing.split(",") if x.strip()]
    for item in LOCAL_BYPASS:
        if item not in items:
            items.append(item)
    return ",".join(items)


def proxy_environment(proxy: str) -> dict:
    env = os.environ.copy()
    no_proxy = merge_no_proxy(env)
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
    proxy = config["proxy"]
    try:
        if not proxy_reachable(proxy):
            print(f"laner-net: proxy is not reachable: {proxy}", file=sys.stderr)
            print("laner-net: run `laner-net detect` or `laner-net set <proxy-url>`", file=sys.stderr)
            return 3
    except ValueError as exc:
        print(f"laner-net: invalid proxy config: {exc}", file=sys.stderr)
        return 3
    env = proxy_environment(proxy)
    os.execvpe(command[0], command, env)
    return 127


def show_status() -> int:
    config = load_config()
    proxy = config["proxy"]
    probe_url = config["probe_url"]
    print(f"laner-net {VERSION}")
    print(f"config: {CONFIG_FILE}")
    print(f"proxy: {proxy}")
    try:
        reachable = proxy_reachable(proxy)
    except ValueError as exc:
        print(f"proxy_valid: no ({exc})")
        return 2
    print(f"proxy_reachable: {'yes' if reachable else 'no'}")
    if reachable:
        ok, detail = curl_probe(proxy, probe_url)
        print(f"proxy_probe: {'ok' if ok else 'fail'} ({detail})")
    print("global_environment_modified: no")
    return 0


def command_detect() -> int:
    config = load_config()
    proxy, notes = detect_proxy(config["probe_url"])
    for note in notes:
        print(note)
    if not proxy:
        print("laner-net: no working localhost proxy found", file=sys.stderr)
        return 4
    config["proxy"] = proxy
    save_config(config)
    print(f"selected_proxy: {proxy}")
    return 0


def command_set(proxy: str) -> int:
    try:
        parsed = parse_proxy(proxy)
    except ValueError as exc:
        print(f"laner-net: {exc}", file=sys.stderr)
        return 2
    config = load_config()
    config["proxy"] = proxy
    save_config(config)
    reachable = tcp_open(parsed.hostname, parsed.port)
    print(f"proxy_saved: {proxy}")
    print(f"proxy_reachable: {'yes' if reachable else 'no'}")
    return 0


def command_test(url: str | None = None) -> int:
    config = load_config()
    target = url or config["probe_url"]
    direct_ok, direct_detail = curl_probe(None, target)
    proxy_ok, proxy_detail = curl_probe(config["proxy"], target)
    print(f"target: {target}")
    print(f"direct: {'ok' if direct_ok else 'fail'} ({direct_detail})")
    print(f"proxy: {'ok' if proxy_ok else 'fail'} ({proxy_detail})")
    return 0 if proxy_ok else 5


def command_env() -> int:
    config = load_config()
    env = proxy_environment(config["proxy"])
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

Examples:
  laner-net curl https://www.google.com/
  laner-net git ls-remote https://github.com/git/git.git HEAD
  laner-net wget https://example.com/file

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
    if command == "detect":
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
