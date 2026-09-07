#!/usr/bin/env bash
set -uo pipefail

ENV_VERSION=1
LOG_FILE=/root/laner/logs/ai-limbs-bootstrap.log
SOURCE_ENV=/etc/ai-limbs/bootstrap-sources.env
TMP_DIR="/var/tmp/ai-limbs-installer-v${ENV_VERSION}"

mkdir -p "$(dirname "$LOG_FILE")" "$TMP_DIR"
[ -f "$SOURCE_ENV" ] && . "$SOURCE_ENV"
export DEBIAN_FRONTEND=noninteractive

PROGRESS_ACTIVE=0
PROGRESS_PERCENT=0

progress_enabled() {
  [ -t 1 ] && [ "${TERM:-dumb}" != "dumb" ]
}

draw_progress() {
  [ "$PROGRESS_ACTIVE" -eq 1 ] || return 0
  progress_enabled || return 0
  local cols width filled empty bar
  cols=$(tput cols 2>/dev/null || printf '80')
  width=20
  [ "$cols" -lt 36 ] && width=12
  [ "$cols" -gt 70 ] && width=30
  filled=$((PROGRESS_PERCENT * width / 100))
  empty=$((width - filled))
  bar="$(printf '%*s' "$filled" '' | tr ' ' '#')$(printf '%*s' "$empty" '' | tr ' ' '-')"
  printf '\r\033[2K进度 [%s] %3d%%' "$bar" "$PROGRESS_PERCENT"
}

show_progress() {
  local percent="${1:-0}"
  [ "$percent" -lt 0 ] && percent=0
  [ "$percent" -gt 100 ] && percent=100
  PROGRESS_PERCENT="$percent"
  PROGRESS_ACTIVE=1
  if progress_enabled; then draw_progress; else printf '进度：%3d%%\n' "$PROGRESS_PERCENT"; fi
}

finish_progress() {
  [ "$PROGRESS_ACTIVE" -eq 1 ] || return 0
  if progress_enabled; then draw_progress; printf '\n'; fi
  PROGRESS_ACTIVE=0
}

abort_progress() {
  if [ "$PROGRESS_ACTIVE" -eq 1 ] && progress_enabled; then printf '\r\033[2K'; fi
  PROGRESS_ACTIVE=0
}

say() {
  if [ "$PROGRESS_ACTIVE" -eq 1 ] && progress_enabled; then printf '\r\033[2K'; fi
  printf '%s\n' "$*" | tee -a "$LOG_FILE"
  if [ "$PROGRESS_ACTIVE" -eq 1 ] && progress_enabled; then draw_progress; fi
}

fail() {
  abort_progress
  say "✗ $*"
  say "  详细日志：$LOG_FILE"
  return 1
}

APT_OPTIONS=(
  -o Acquire::Retries=0
  -o Acquire::http::Timeout=12
  -o Acquire::https::Timeout=12
)

write_apt_sources() {
  local base="$1"
  cat > /etc/apt/sources.list <<EOL
deb $base noble main restricted universe multiverse
deb $base noble-updates main restricted universe multiverse
deb $base noble-backports main restricted universe multiverse
deb $base noble-security main restricted universe multiverse
EOL
}

try_apt_source() {
  local source="$1"
  write_apt_sources "$source"
  rm -rf /var/lib/apt/lists/*
  mkdir -p /var/lib/apt/lists/partial
  apt-get "${APT_OPTIONS[@]}" update >>"$LOG_FILE" 2>&1
}

select_apt_source() {
  local preferred="${AI_LIMBS_PREFERRED_APT_SOURCE:-}"
  local candidates="
$preferred
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
https://mirrors.bfsu.edu.cn/ubuntu-ports/
https://mirrors.aliyun.com/ubuntu-ports/
https://mirrors.ustc.edu.cn/ubuntu-ports/
http://ports.ubuntu.com/ubuntu-ports/
"
  local seen="|" source
  APT_SELECTED=""
  for source in $candidates; do
    [ -n "$source" ] || continue
    case "$seen" in *"|$source|"*) continue ;; esac
    seen="${seen}${source}|"
    say "  尝试 APT 源：$source"
    if try_apt_source "$source"; then
      APT_SELECTED="$source"
      say "  ✓ APT 源可用：$source"
      return 0
    fi
    say "  ↳ 当前源不可用，切换备用源。"
  done
  return 1
}

run_apt() {
  apt-get "${APT_OPTIONS[@]}" "$@" >>"$LOG_FILE" 2>&1
}

install_node_from_nodesource() {
  say "  尝试 NodeSource 24.x。"
  install -d -m 0755 /etc/apt/keyrings
  if ! curl -fsSL --connect-timeout 10 --max-time 30 \
      https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
      | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg >>"$LOG_FILE" 2>&1; then
    return 1
  fi
  printf '%s\n' \
    'deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_24.x nodistro main' \
    > /etc/apt/sources.list.d/nodesource.list
  if ! run_apt update || ! run_apt install -y --no-install-recommends nodejs; then
    rm -f /etc/apt/sources.list.d/nodesource.list
    write_apt_sources "$APT_SELECTED"
    run_apt update || true
    return 1
  fi
  node --version 2>/dev/null | grep -q '^v24\.'
}

install_node_archive() {
  local base="$1" label="$2"
  local sums="$TMP_DIR/SHASUMS256.txt" record hash filename extracted target bin
  say "  尝试 $label。"
  rm -f "$sums"
  curl -fsSL --connect-timeout 10 --max-time 45 "$base/SHASUMS256.txt" -o "$sums" || return 1
  record=$(awk '$2 ~ /linux-arm64\.tar\.xz$/ {print $1 " " $2; exit}' "$sums")
  [ -n "$record" ] || return 1
  hash=${record%% *}
  filename=${record#* }
  curl -fL --connect-timeout 10 --max-time 300 "$base/$filename" -o "$TMP_DIR/$filename" \
    >>"$LOG_FILE" 2>&1 || return 1
  (cd "$TMP_DIR" && printf '%s  %s\n' "$hash" "$filename" | sha256sum -c -) \
    >>"$LOG_FILE" 2>&1 || return 1
  extracted="$TMP_DIR/${filename%.tar.xz}"
  rm -rf "$extracted"
  tar -xJf "$TMP_DIR/$filename" -C "$TMP_DIR" >>"$LOG_FILE" 2>&1 || return 1
  target="/usr/local/lib/nodejs/${filename%.tar.xz}"
  mkdir -p /usr/local/lib/nodejs /usr/local/bin
  rm -rf "$target"
  mv "$extracted" "$target" || return 1
  for bin in node npm npx corepack; do
    [ -e "$target/bin/$bin" ] && ln -sf "$target/bin/$bin" "/usr/local/bin/$bin"
  done
  node --version 2>/dev/null | grep -q '^v24\.'
}

install_node24() {
  if node --version 2>/dev/null | grep -q '^v24\.'; then
    say "  ✓ Node.js 24 已存在。"
    return 0
  fi
  if install_node_from_nodesource; then
    say "  ✓ Node.js 24 已通过 NodeSource 安装。"
    return 0
  fi
  say "  ↳ NodeSource 不可用，切换二进制发行包。"
  rm -f /etc/apt/sources.list.d/nodesource.list
  if install_node_archive "https://cdn.npmmirror.com/binaries/node/latest-v24.x" "npmmirror Node.js 镜像"; then
    say "  ✓ Node.js 24 已通过 npmmirror 安装并校验。"
    return 0
  fi
  say "  ↳ npmmirror 不可用，切换 Node.js 官方站。"
  if install_node_archive "https://nodejs.org/dist/latest-v24.x" "Node.js 官方发行包"; then
    say "  ✓ Node.js 24 已通过官方发行包安装并校验。"
    return 0
  fi
  return 1
}

install_npm_tools() {
  local preferred="${AI_LIMBS_PREFERRED_NPM_SOURCE:-}"
  local candidates="
$preferred
https://registry.npmmirror.com/
https://mirrors.cloud.tencent.com/npm/
https://repo.huaweicloud.com/repository/npm/
https://registry.npmjs.org/
"
  local seen="|" registry
  for registry in $candidates; do
    [ -n "$registry" ] || continue
    case "$seen" in *"|$registry|"*) continue ;; esac
    seen="${seen}${registry}|"
    say "  尝试 npm 源：$registry"
    if npm install -g corepack@0.35.0 pnpm@11.24.0 --registry="$registry" \
        >>"$LOG_FILE" 2>&1; then
      command -v corepack >/dev/null 2>&1 && command -v pnpm >/dev/null 2>&1 && return 0
    fi
    say "  ↳ 当前 npm 源不可用，切换备用源。"
  done
  return 1
}

say ""
say "AI Limbs 正在初始化 Ubuntu 标准开发环境 v$ENV_VERSION"
say "首次初始化仅执行一次，请保持网络连接并不要关闭 Ubuntu。"
say ""
show_progress 0

say "[1/5] 检查软件下载源 ..."
show_progress 3
select_apt_source || { fail "所有 APT 下载源均不可用。"; exit 1; }
show_progress 10
say "[1/5] 检查软件下载源 ✓"

say "[2/5] 修复并更新 Ubuntu（这一步可能需要几分钟）..."
show_progress 12
dpkg --configure -a >>"$LOG_FILE" 2>&1 || true
show_progress 18
run_apt -f install -y || { fail "Ubuntu 中断恢复失败。"; exit 1; }
show_progress 24
dpkg --configure -a >>"$LOG_FILE" 2>&1 || { fail "dpkg 恢复失败。"; exit 1; }
show_progress 30
run_apt -y upgrade || { fail "Ubuntu 基础系统更新失败。"; exit 1; }
show_progress 40
say "[2/5] 修复并更新 Ubuntu ✓"

say "[3/5] 安装标准开发工具 ..."
show_progress 45
BASE_PACKAGES="
git gh git-lfs curl wget jq ripgrep fd-find findutils coreutils procps
iproute2 dnsutils openssh-client rsync tar zip unzip xz-utils ca-certificates
gnupg python3 python3-pip python3-venv pipx
build-essential gcc g++ make cmake ninja-build pkg-config openjdk-17-jdk
"
run_apt install -y --no-install-recommends $BASE_PACKAGES || {
  fail "标准开发工具安装失败。"; exit 1;
}
if command -v fdfind >/dev/null 2>&1; then
  mkdir -p /usr/local/bin
  ln -sf "$(command -v fdfind)" /usr/local/bin/fd
fi
show_progress 70
say "[3/5] 安装标准开发工具 ✓"

say "[4/5] 配置 Node.js / npm / pnpm ..."
show_progress 74
install_node24 || { fail "Node.js 24 的全部下载路线均失败。"; exit 1; }
show_progress 84
install_npm_tools || { fail "corepack / pnpm 的全部 npm 下载源均失败。"; exit 1; }
show_progress 90
say "[4/5] 配置 Node.js / npm / pnpm ✓"

say "[5/5] 验证开发环境 ..."
show_progress 92
REQUIRED_COMMANDS="
git git-lfs gh curl wget jq rg fd python3 pip3 pipx
node npm corepack pnpm gcc g++ make cmake ninja pkg-config
java ssh rsync zip unzip xz
"
missing=""
for cmd in $REQUIRED_COMMANDS; do
  command -v "$cmd" >/dev/null 2>&1 || missing="$missing $cmd"
done
show_progress 95
for own_tool in ail-tool ail-status ail-preflight ail-cloud-build ail-cloud-runs ail-cloud-watch laner-edit; do
  [ -x "/root/laner/bin/$own_tool" ] || missing="$missing $own_tool"
done
[ -z "$missing" ] || { fail "环境验证失败，缺少：$missing"; exit 1; }
node --version 2>/dev/null | grep -q '^v24\.' || { fail "Node.js 不是 24.x。"; exit 1; }
show_progress 97
say "[5/5] 验证开发环境 ✓"

say "正在清理安装文件 ..."
show_progress 98
apt-get clean >>"$LOG_FILE" 2>&1 || true
rm -rf /var/lib/apt/lists/* "$TMP_DIR"
show_progress 99
say "  ✓ 下载缓存已清理"
say "  ✓ 环境状态将由 bootstrap 保存"
rm -f -- "$0" 2>/dev/null || true
say "  ✓ 临时安装程序已卸载"
show_progress 100
finish_progress
say ""
say "AI Limbs Ubuntu 标准开发环境初始化完成。"
exit 0
