#!/usr/bin/env bash
set -uo pipefail

ENV_VERSION=1
STATE_DIR=/etc/ai-limbs
STATE_FILE="$STATE_DIR/environment-state.json"
LOCK_DIR=/var/lock/ai-limbs-bootstrap.lock
PID_FILE="$LOCK_DIR/pid"
LOG_FILE=/root/laner/logs/ai-limbs-bootstrap.log
INSTALLER="/usr/local/lib/ai-limbs/installer-v${ENV_VERSION}.sh"
MODE="${1:---background}"

log() {
  printf '[AI Limbs bootstrap] %s\n' "$*" | tee -a "$LOG_FILE"
}

write_state() {
  local status="$1" choice="$2" completed_at="${3:-}"
  mkdir -p "$STATE_DIR"
  printf '{"schema_version":1,"environment_version":%s,"status":"%s","choice":"%s","completed_at":"%s"}\n' \
    "$ENV_VERSION" "$status" "$choice" "$completed_at" > "$STATE_FILE.tmp"
  mv "$STATE_FILE.tmp" "$STATE_FILE"
}

is_complete() {
  [ -f "$STATE_FILE" ] &&
    grep -q "\"environment_version\":$ENV_VERSION" "$STATE_FILE" &&
    grep -q '"status":"complete"' "$STATE_FILE"
}

is_declined() {
  [ -f "$STATE_FILE" ] &&
    grep -q "\"environment_version\":$ENV_VERSION" "$STATE_FILE" &&
    grep -q '"choice":"declined"' "$STATE_FILE"
}

is_install_requested() {
  [ -f "$STATE_FILE" ] &&
    grep -q "\"environment_version\":$ENV_VERSION" "$STATE_FILE" &&
    grep -q '"choice":"install_requested"' "$STATE_FILE"
}

mkdir -p "$STATE_DIR" "$(dirname "$LOCK_DIR")" "$(dirname "$LOG_FILE")"

if is_complete; then
  rm -f "$INSTALLER" 2>/dev/null || true
  [ "$MODE" = "--force-prompt" ] && printf "\nAI Limbs 标准开发环境已经安装完成，无需重复安装。\n\n"
  exit 0
fi

if is_declined && [ "$MODE" != "--force-prompt" ]; then
  rm -f "$INSTALLER" 2>/dev/null || true
  exit 0
fi

[ "$MODE" = "--prompt" ] || [ "$MODE" = "--force-prompt" ] || exit 0
[ -t 0 ] || exit 0

show_menu() {
  printf '\n'
  printf '┌──────────────────────────────────┐\n'
  printf '│       AI Limbs 开发环境          │\n'
  printf '├──────────────────────────────────┤\n'
  printf '│ 可选安装标准开发工具。           │\n'
  printf '│ 安装过程需要联网，并显示进度。   │\n'
  printf '│                                  │\n'
  printf '│   1. 一键安装                    │\n'
  printf '│   2. 稍后再说                    │\n'
  printf '│   3. 不再提醒                    │\n'
  printf '└──────────────────────────────────┘\n'
  printf '\n请选择 [1/2/3]：'
}

lock_is_live() {
  local pid=""
  [ -f "$PID_FILE" ] || return 1
  pid=$(cat "$PID_FILE" 2>/dev/null || true)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$pid" 2>/dev/null || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null |
    grep -Eq 'ai_limbs_bootstrap|installer-v[0-9]+\.sh|/usr/local/lib/ai-limbs/bootstrap\.sh'
}

acquire_lock() {
  if [ -d "$LOCK_DIR" ]; then
    if lock_is_live; then
      log "另一个 Ubuntu 会话正在安装开发环境，等待它完成。"
      for _ in $(seq 1 900); do
        is_complete && return 2
        [ -d "$LOCK_DIR" ] || break
        lock_is_live || break
        sleep 1
      done
      if [ -d "$LOCK_DIR" ] && lock_is_live; then
        log "等待安装超时；稍后可重新进入前台终端继续。"
        return 1
      fi
    fi
    rm -rf "$LOCK_DIR" 2>/dev/null || true
  fi
  mkdir "$LOCK_DIR" 2>/dev/null || return 1
  printf '%s\n' "$$" > "$PID_FILE"
  return 0
}

cleanup_lock() {
  rm -rf "$LOCK_DIR" 2>/dev/null || true
}

interrupted() {
  write_state incomplete install_requested
  log "安装被中断；下次进入前台 Ubuntu 终端时会自动恢复。"
  cleanup_lock
  exit 130
}

run_installer() {
  local lock_rc=0
  acquire_lock || lock_rc=$?
  if [ "$lock_rc" -eq 2 ]; then
    return 0
  fi
  [ "$lock_rc" -eq 0 ] || return 1

  trap interrupted INT TERM HUP
  trap cleanup_lock EXIT
  write_state running install_requested

  if [ ! -x "$INSTALLER" ]; then
    write_state incomplete install_requested
    log "缺少一次性安装器：$INSTALLER"
    return 1
  fi

  if "$INSTALLER"; then
    local completed_at
    completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)
    write_state complete install_requested "$completed_at"
    rm -f "$INSTALLER" 2>/dev/null || true
    log "标准开发环境 v$ENV_VERSION 已就绪。"
    return 0
  fi

  write_state incomplete install_requested
  log "安装未完成；下次进入前台 Ubuntu 终端时会继续恢复。"
  return 1
}

if is_install_requested; then
  printf '\n检测到上次开发环境安装未完成，正在继续安装。\n'
  run_installer
  exit $?
fi

while true; do
  show_menu
  IFS= read -r choice || choice=2
  case "$choice" in
    1)
      write_state incomplete install_requested
      printf '\n已选择一键安装。\n'
      run_installer
      exit $?
      ;;
    2)
      write_state not_installed pending
      printf '\n已跳过本次安装，下次启动 Ubuntu 时仍会提醒。\n\n'
      exit 0
      ;;
    3)
      write_state not_installed declined
      rm -f "$INSTALLER" 2>/dev/null || true
      printf '\n已关闭开发环境安装提醒。以后仍可从环境配置中手动安装。\n\n'
      exit 0
      ;;
    *)
      printf '\n请输入 1、2 或 3。\n'
      ;;
  esac
done
