#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Iterable

VERSION = "1.0.1"
KNOWN_ROOTS = (Path("/root/laner/bin"), Path("/root/laner/tools"))
SAFE_VERSION_PROBE = {
    "git", "gh", "git-lfs", "curl", "wget", "jq", "rg", "fd",
    "python3", "pip3", "pipx", "node", "npm", "pnpm", "corepack",
    "gcc", "g++", "make", "cmake", "ninja", "pkg-config", "java",
    "ssh", "rsync", "zip", "unzip", "xz",
}
TOOL_SPECS = {
    "git": ("Git", "版本控制与代码仓库", ["version control", "source control", "repository", "版本控制", "代码仓库", "仓库"]),
    "gh": ("GitHub CLI", "GitHub 仓库、Issue、PR 与 Actions", ["github", "github cli", "pull request", "issue", "actions", "github仓库"]),
    "git-lfs": ("Git LFS", "Git 大文件支持", ["lfs", "large file", "大文件"]),
    "curl": ("curl", "HTTP/API 请求与下载", ["http", "https", "api", "download", "网络请求", "下载", "接口"]),
    "wget": ("wget", "文件下载", ["download", "下载文件", "下载"]),
    "jq": ("jq", "JSON 查询、解析与转换", ["json", "parse json", "json processor", "处理json", "解析json", "json处理"]),
    "rg": ("ripgrep", "高速文本与源码搜索", ["ripgrep", "text search", "source search", "搜索源码", "文本搜索", "代码搜索", "查代码"]),
    "fd": ("fd", "文件名快速搜索", ["file search", "find files", "文件搜索", "查找文件", "找文件"]),
    "ssh": ("OpenSSH Client", "SSH 远程登录与连接", ["ssh", "remote shell", "远程登录", "远程连接"]),
    "rsync": ("rsync", "目录与文件同步", ["sync files", "file sync", "文件同步", "同步文件"]),
    "python3": ("Python 3", "Python 运行时", ["python", "python3", "脚本", "python开发"]),
    "pip3": ("pip", "Python 包管理器", ["pip", "python package", "python包", "python依赖"]),
    "pipx": ("pipx", "隔离安装 Python CLI", ["pipx", "python cli", "python工具"]),
    "node": ("Node.js", "JavaScript/Node.js 运行时", ["nodejs", "javascript", "js", "javascript运行时", "node开发"]),
    "npm": ("npm", "Node.js 包管理器", ["npm", "node package", "node包", "javascript包"]),
    "pnpm": ("pnpm", "Node.js 包管理器", ["pnpm", "node package", "node包"]),
    "corepack": ("Corepack", "Node 包管理器版本桥接", ["corepack", "package manager", "包管理器"]),
    "gcc": ("GCC", "C 编译器", ["c compiler", "compile c", "c编译器", "编译c", "编译"]),
    "g++": ("G++", "C++ 编译器", ["cpp compiler", "c++", "c++编译器", "编译c++", "编译"]),
    "make": ("GNU Make", "构建工具", ["build", "make", "构建", "编译"]),
    "cmake": ("CMake", "跨平台构建系统", ["cmake", "build system", "构建系统", "构建", "编译"]),
    "ninja": ("Ninja", "高速构建工具", ["ninja", "build", "构建"]),
    "pkg-config": ("pkg-config", "开发库元数据查询", ["pkgconfig", "library metadata", "库信息", "依赖信息"]),
    "java": ("Java", "OpenJDK Java 运行时/开发环境", ["java", "jdk", "openjdk", "java开发", "android"]),
    "ail-tool": ("AI Limbs Tool Manager", "Ubuntu 实时工具发现与环境体检", ["tool manager", "software manager", "工具查询", "软件管理", "工具管理"]),
    "ail-status": ("AI Limbs Status", "AI Limbs 开发环境状态检查", ["environment status", "状态检查", "环境状态"]),
    "ail-preflight": ("AI Limbs Preflight", "开发前项目与环境体检", ["preflight", "开发前体检", "提交前检查"]),
    "ail-cloud-build": ("AI Limbs Cloud Build", "触发 GitHub Actions 云端构建", ["cloud build", "github actions", "云端构建", "云构建"]),
    "ail-cloud-runs": ("AI Limbs Cloud Runs", "查看 GitHub Actions 构建记录", ["build runs", "actions runs", "构建记录"]),
    "ail-cloud-watch": ("AI Limbs Cloud Watch", "跟踪云端构建过程", ["watch build", "构建跟踪", "构建监控"]),
    "laner-edit": ("Laner Edit", "AI Limbs 编辑辅助工具", ["edit helper", "编辑辅助", "代码编辑"]),
}
BASELINE_GROUPS = {
    "Core": ["git", "gh", "git-lfs", "curl", "wget", "jq", "rg", "fd", "ssh", "rsync", "zip", "unzip", "xz"],
    "Python": ["python3", "pip3", "pipx"],
    "Node": ["node", "npm", "corepack", "pnpm"],
    "Build": ["gcc", "g++", "make", "cmake", "ninja", "pkg-config", "java"],
    "AI Limbs": ["ail-tool", "ail-status", "ail-preflight", "ail-cloud-build", "ail-cloud-runs", "ail-cloud-watch", "laner-edit"],
}

@dataclass
class Record:
    name: str
    display_name: str = ""
    kind: str = "command"
    available: bool = True
    version: str = ""
    executable: str = ""
    source: str = ""
    package: str = ""
    architecture: str = ""
    description: str = ""
    aliases: list[str] = field(default_factory=list)
    evidence: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return asdict(self)
def run(command: list[str], timeout: float = 4.0) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
            env=os.environ.copy(),
        )
        return proc.returncode, proc.stdout.strip()
    except (FileNotFoundError, PermissionError, subprocess.TimeoutExpired):
        return 127, ""


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip().lower().replace("_", " "))


def first_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip()
        if line:
            return line[:180]
    return ""


def spec_for(name: str) -> tuple[str, str, list[str]]:
    return TOOL_SPECS.get(name, (name, "", []))
def probe_version(name: str, executable: str) -> str:
    if name not in SAFE_VERSION_PROBE or not executable:
        return ""
    attempts = ([executable, "--version"], [executable, "-V"], [executable, "version"])
    for command in attempts:
        rc, out = run(list(command), timeout=2.0)
        if rc == 0 and out:
            return first_line(out)
    return ""


@lru_cache(maxsize=256)
def dpkg_metadata(package: str) -> tuple[str, str]:
    rc, out = run([
        "dpkg-query", "-W", "-f=${Version}\t${Architecture}", package
    ])
    if rc != 0 or not out:
        return "", ""
    parts = out.split("\t", 1)
    return parts[0], parts[1] if len(parts) > 1 else ""


@lru_cache(maxsize=512)
def dpkg_owner(path: str) -> tuple[str, str, str]:
    candidates = [path]
    real = os.path.realpath(path)
    if real != path:
        candidates.append(real)
    for candidate in candidates:
        rc, out = run(["dpkg-query", "-S", candidate], timeout=2.0)
        if rc == 0 and out:
            package = out.splitlines()[0].split(": ", 1)[0].split(",", 1)[0]
            package = package.split(":", 1)[0]
            version, arch = dpkg_metadata(package)
            return package, version, arch
    return "", "", ""
def quick_command_record(name: str) -> Record:
    display, description, aliases = spec_for(name)
    executable = shutil.which(name) or ""
    if executable.startswith("/root/laner/bin/"):
        source = "ai-limbs"
    elif executable.startswith("/root/laner/tools/"):
        source = "ai-limbs-tool"
    elif executable.startswith("/usr/local/"):
        source = "local"
    else:
        source = "path" if executable else "not-installed"
    return Record(name=name, display_name=display, available=bool(executable),
                  executable=executable, source=source, description=description,
                  aliases=list(aliases), evidence=[f"command -v {name}: {executable or 'missing'}"])


def command_record(name: str) -> Record:
    display, description, aliases = spec_for(name)
    executable = shutil.which(name) or ""
    record = Record(
        name=name,
        display_name=display,
        available=bool(executable),
        executable=executable,
        description=description,
        aliases=list(aliases),
    )
    if not executable:
        record.source = "not-installed"
        record.evidence = [f"command -v {name}: missing"]
        return record

    resolved = os.path.realpath(executable)
    if executable.startswith("/root/laner/bin/"):
        record.source = "ai-limbs"
        record.package = name
    elif executable.startswith("/root/laner/tools/") or resolved.startswith("/root/laner/tools/"):
        record.source = "ai-limbs-tool"
        record.package = name
    else:
        package, version, arch = dpkg_owner(executable)
        record.package, record.version, record.architecture = package, version, arch
        record.source = "dpkg" if package else ("local" if executable.startswith("/usr/local/") else "path")
    if not record.version:
        record.version = probe_version(name, executable)
    record.evidence = [f"command -v {name}: {executable}"]
    return record
def iter_path_records() -> list[Record]:
    seen_dirs: set[str] = set()
    names: dict[str, str] = {}
    raw_dirs = [Path(p) for p in os.environ.get("PATH", "").split(":") if p]
    raw_dirs.extend(KNOWN_ROOTS)
    for directory in raw_dirs:
        key = str(directory)
        if key in seen_dirs or not directory.is_dir():
            continue
        seen_dirs.add(key)
        try:
            for entry in directory.iterdir():
                if entry.name.startswith(".") or entry.name in names:
                    continue
                try:
                    if entry.is_file() and os.access(entry, os.X_OK):
                        names[entry.name] = str(entry)
                except OSError:
                    continue
        except OSError:
            continue
    records = []
    for name, executable in names.items():
        display, description, aliases = spec_for(name)
        source = "ai-limbs" if executable.startswith("/root/laner/bin/") else "path"
        records.append(Record(name=name, display_name=display, executable=executable,
                              source=source, description=description, aliases=list(aliases),
                              evidence=[f"PATH:{executable}"]))
    return records
def dpkg_records() -> list[Record]:
    rc, out = run(["dpkg-query", "-W", "-f=${binary:Package}\t${Version}\t${Architecture}\n"], timeout=8.0)
    if rc != 0:
        return []
    records: list[Record] = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        package = parts[0].split(":", 1)[0]
        records.append(Record(
            name=package,
            display_name=package,
            kind="system-package",
            version=parts[1],
            source="dpkg",
            package=package,
            architecture=parts[2],
            evidence=["dpkg-query -W"],
        ))
    return records


def pip_records() -> list[Record]:
    rc, out = run(["python3", "-m", "pip", "list", "--format=json"], timeout=8.0)
    if rc != 0 or not out:
        return []
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return []
    return [Record(name=p["name"], display_name=p["name"], kind="python-package",
                   version=p.get("version", ""), source="pip", package=p["name"],
                   evidence=["python3 -m pip list --format=json"])
            for p in data if isinstance(p, dict) and p.get("name")]
def pipx_records() -> list[Record]:
    if not shutil.which("pipx"):
        return []
    rc, out = run(["pipx", "list", "--json"], timeout=8.0)
    if rc != 0 or not out:
        return []
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return []
    records: list[Record] = []
    for name, meta in (data.get("venvs") or {}).items():
        main = (meta or {}).get("metadata", {}).get("main_package", {})
        package = main.get("package") or name
        apps = main.get("apps") or []
        records.append(Record(
            name=package,
            display_name=package,
            kind="python-cli-package",
            version=main.get("package_version", ""),
            source="pipx",
            package=package,
            evidence=["pipx list --json"] + [f"app:{app}" for app in apps[:8]],
        ))
    return records


def npm_records() -> list[Record]:
    if not shutil.which("npm"):
        return []
    rc, out = run(["npm", "-g", "ls", "--depth=0", "--json"], timeout=8.0)
    if rc not in (0, 1) or not out:
        return []
    try:
        deps = (json.loads(out).get("dependencies") or {})
    except json.JSONDecodeError:
        return []
    return [Record(name=name, display_name=name, kind="node-package",
                   version=(meta or {}).get("version", ""), source="npm",
                   package=name, evidence=["npm -g ls --depth=0 --json"])
            for name, meta in deps.items()]
def pnpm_records() -> list[Record]:
    if not shutil.which("pnpm"):
        return []
    rc, out = run(["pnpm", "list", "-g", "--depth=0", "--json"], timeout=8.0)
    if rc != 0 or not out:
        return []
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return []
    roots = data if isinstance(data, list) else [data]
    records: list[Record] = []
    for root in roots:
        deps = (root or {}).get("dependencies") or {}
        for name, meta in deps.items():
            records.append(Record(name=name, display_name=name, kind="node-package",
                                  version=(meta or {}).get("version", ""), source="pnpm",
                                  package=name, evidence=["pnpm list -g --depth=0 --json"]))
    return records


def package_records() -> list[Record]:
    collectors = (dpkg_records, pip_records, pipx_records, npm_records, pnpm_records)
    records: list[Record] = []
    with ThreadPoolExecutor(max_workers=len(collectors)) as executor:
        for result in executor.map(lambda collector: collector(), collectors):
            records.extend(result)
    return records


def enrich(record: Record) -> Record:
    if record.kind != "command" or not record.available:
        return record
    fresh = command_record(record.name)
    if fresh.available:
        return fresh
    return record
def score_record(query: str, record: Record) -> int:
    q = normalize(query)
    if not q:
        return 0
    name = normalize(record.name)
    display = normalize(record.display_name)
    description = normalize(record.description)
    aliases = [normalize(alias) for alias in record.aliases]
    package = normalize(record.package)
    score = 0
    if q == name:
        score += 140
    if q == package and package:
        score += 120
    if q == display and display:
        score += 110
    if q in aliases:
        score += 110
    haystack = " ".join([name, display, description, package, " ".join(aliases)])
    if q in haystack:
        score += 60
    for alias in aliases:
        if not alias:
            continue
        if q == alias or (len(alias) >= 3 and (alias in q or q in alias)):
            score += 50
    tokens = re.findall(r"[a-z0-9+._-]+|[\u4e00-\u9fff]+", q)
    for token in tokens:
        if len(token) > 1 and token in haystack:
            score += 8
    if score > 0 and record.available:
        score += 5
    return score


def search_records(query: str, limit: int = 20, deep: bool = False) -> list[Record]:
    by_key: dict[tuple[str, str, str], Record] = {}
    for record in iter_path_records():
        by_key[(record.name, record.kind, record.source)] = record
    for name in TOOL_SPECS:
        known = quick_command_record(name)
        by_key[(known.name, known.kind, known.source)] = known
    if deep:
        for record in package_records():
            by_key[(record.name, record.kind, record.source)] = record
    scored = [(score_record(query, record), record) for record in by_key.values()]
    scored = [(score, record) for score, record in scored if score > 0]
    scored.sort(key=lambda item: (-item[0], not item[1].available, item[1].name.lower(), item[1].source))
    return [enrich(record) for _, record in scored[:max(1, limit)]]
def info_records(name: str, deep: bool = False) -> list[Record]:
    target = normalize(name)
    records: list[Record] = []
    command = command_record(name)
    if command.available or name in TOOL_SPECS:
        records.append(command)
    if deep or not command.available:
        for record in package_records():
            if normalize(record.name) == target or normalize(record.package) == target:
                records.append(record)
    seen: set[tuple[str, str, str]] = set()
    unique: list[Record] = []
    for record in records:
        key = (record.name, record.kind, record.source)
        if key not in seen:
            seen.add(key)
            unique.append(record)
    return unique


def list_records(include_packages: bool, source: str | None, limit: int) -> list[Record]:
    records = iter_path_records()
    if include_packages:
        records.extend(package_records())
    if source:
        records = [record for record in records if record.source == source]
    records.sort(key=lambda record: (record.name.lower(), record.kind, record.source))
    selected = records[:max(1, limit)]
    return [enrich(record) if record.kind == "command" else record for record in selected]


def read_environment_state() -> dict:
    path = Path("/etc/ai-limbs/environment-state.json")
    try:
        data = json.loads(path.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def node24_ok(record: Record) -> bool:
    if not record.available:
        return False
    text = record.version.lower()
    return bool(re.search(r"(?:^|\D)v?24(?:\.|\D)", text))
def doctor_result(deep: bool = False) -> dict:
    groups: dict[str, list[dict]] = {}
    ready = True
    for group, names in BASELINE_GROUPS.items():
        items: list[dict] = []
        for name in names:
            record = command_record(name) if deep else quick_command_record(name)
            ok = record.available
            requirement = "available"
            if name == "node":
                requirement = "Node.js 24.x"
                if record.available and not record.version:
                    record.version = probe_version(name, record.executable)
                ok = node24_ok(record)
            ready = ready and ok
            item = record.as_dict()
            item["ok"] = ok
            item["requirement"] = requirement
            items.append(item)
        groups[group] = items
    return {
        "environment_version": 1,
        "state": read_environment_state(),
        "ready": ready,
        "groups": groups,
    }


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text().splitlines()
    except OSError:
        return values
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip("'\"")
    return values
def sources_result() -> dict:
    apt_lines: list[str] = []
    apt_path = Path("/etc/apt/sources.list")
    try:
        apt_lines = [line.strip() for line in apt_path.read_text().splitlines()
                     if line.strip() and not line.lstrip().startswith("#")]
    except OSError:
        pass
    preferred = parse_env_file(Path("/etc/ai-limbs/bootstrap-sources.env"))
    pip_index = os.environ.get("PIP_INDEX_URL", "")
    if not pip_index:
        rc, out = run(["python3", "-m", "pip", "config", "get", "global.index-url"], timeout=3.0)
        if rc == 0:
            pip_index = first_line(out)
    npm_registry = ""
    if shutil.which("npm"):
        rc, out = run(["npm", "config", "get", "registry"], timeout=3.0)
        if rc == 0:
            npm_registry = first_line(out)
    pnpm_registry = ""
    if shutil.which("pnpm"):
        rc, out = run(["pnpm", "config", "get", "registry"], timeout=3.0)
        if rc == 0:
            pnpm_registry = first_line(out)
    return {
        "preferred": preferred,
        "apt": apt_lines,
        "pip": pip_index,
        "npm": npm_registry,
        "pnpm": pnpm_registry,
    }
def print_record(record: Record) -> None:
    mark = "✓" if record.available else "✗"
    title = record.display_name or record.name
    print(f"{mark} {record.name} — {title}" if title != record.name else f"{mark} {record.name}")
    if record.description:
        print(f"  用途: {record.description}")
    print(f"  状态: {'可用' if record.available else '未安装/不可用'}")
    if record.version:
        print(f"  版本: {record.version}")
    if record.executable:
        print(f"  路径: {record.executable}")
    if record.source:
        print(f"  来源: {record.source}")
    if record.package:
        print(f"  软件包: {record.package}")
    if record.architecture:
        print(f"  架构: {record.architecture}")
    if record.evidence:
        print(f"  证据: {'; '.join(record.evidence[:3])}")


def emit_records(records: Iterable[Record], json_mode: bool) -> None:
    items = list(records)
    if json_mode:
        print(json.dumps([record.as_dict() for record in items], ensure_ascii=False, indent=2))
        return
    if not items:
        print("没有找到匹配项。")
        return
    for index, record in enumerate(items):
        if index:
            print()
        print_record(record)
def print_doctor(result: dict, json_mode: bool) -> None:
    if json_mode:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    print("AI Limbs Development Environment v1")
    state = result.get("state") or {}
    if state:
        print(f"环境状态: {state.get('status', 'unknown')} / 选择: {state.get('choice', 'unknown')}")
    else:
        print("环境状态: 尚未记录")
    print()
    for group, items in result["groups"].items():
        print(group)
        for item in items:
            mark = "✓" if item["ok"] else "✗"
            version = f"  {item['version']}" if item.get("version") else ""
            requirement = item.get("requirement", "")
            suffix = "" if item["ok"] or requirement == "available" else f"  [需要 {requirement}]"
            print(f"  {mark} {item['name']}{version}{suffix}")
        print()
    print(f"Result: {'READY' if result['ready'] else 'NOT READY'}")


def print_sources(result: dict, json_mode: bool) -> None:
    if json_mode:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    print("AI Limbs Ubuntu 软件源")
    preferred = result.get("preferred") or {}
    if preferred:
        print("\n初始化器首选源:")
        for key, value in sorted(preferred.items()):
            print(f"  {key} = {value}")
    print("\nAPT 当前源:")
    for line in result.get("apt") or []:
        print(f"  {line}")
    for label in ("pip", "npm", "pnpm"):
        value = result.get(label) or "未配置/不可用"
        print(f"\n{label}: {value}")


def add_json_flag(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--json", action="store_true", help="以 JSON 输出，供 AI/脚本消费")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ail-tool",
        description="AI Limbs Ubuntu 实时工具发现、描述与标准开发环境体检工具",
    )
    parser.add_argument("--version", action="version", version=f"ail-tool {VERSION}")
    sub = parser.add_subparsers(dest="command")

    search = sub.add_parser("search", help="按名称、用途或能力搜索工具")
    search.add_argument("query", nargs="+", help="例如 git、github、json、搜索源码")
    search.add_argument("--limit", type=int, default=20)
    search.add_argument("--deep", action="store_true", help="同时扫描 dpkg/pip/pipx/npm/pnpm 包记录")
    add_json_flag(search)

    info = sub.add_parser("info", help="查看一个工具/包的实时详情")
    info.add_argument("name")
    info.add_argument("--deep", action="store_true", help="同时检查包管理器记录")
    add_json_flag(info)
    listing = sub.add_parser("list", help="列出当前可执行工具；--all 同时列包管理器记录")
    listing.add_argument("--all", action="store_true", dest="include_packages")
    listing.add_argument("--source", choices=[
        "ai-limbs", "ai-limbs-tool", "path", "local", "dpkg", "pip", "pipx", "npm", "pnpm"
    ])
    listing.add_argument("--limit", type=int, default=100)
    add_json_flag(listing)

    doctor = sub.add_parser("doctor", help="检查 AI Limbs 标准开发环境是否完整")
    doctor.add_argument("--deep", action="store_true", help="同时查询 dpkg 包归属、版本和架构；较慢")
    add_json_flag(doctor)

    sources = sub.add_parser("sources", help="查看当前软件源与初始化器首选源")
    add_json_flag(sources)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.command:
        parser.print_help()
        return 0
    if args.command == "search":
        query = " ".join(args.query)
        records = search_records(query, args.limit, args.deep)
        if not args.json:
            print(f"AI Limbs Tool Search: {query}\n")
        emit_records(records, args.json)
        return 0 if records else 1

    if args.command == "info":
        records = info_records(args.name, args.deep)
        emit_records(records, args.json)
        return 0 if records and any(record.available for record in records) else 1

    if args.command == "list":
        records = list_records(args.include_packages, args.source, args.limit)
        emit_records(records, args.json)
        return 0

    if args.command == "doctor":
        result = doctor_result(args.deep)
        print_doctor(result, args.json)
        return 0 if result["ready"] else 2

    if args.command == "sources":
        print_sources(sources_result(), args.json)
        return 0

    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())
