---
repository: https://github.com/AdrienFan/AI-Limbs-Public-Build.git
branch: feat/v0.6.2-shared-tab-safe-stop
baseline: ec6adc3
terminal_branch: feat/v0.6.2-shared-tab-safe-stop
terminal_baseline: 03265b4
terminal_commit: 04f4db0
status: ready-for-ci
---

# AI Limbs V0.6.2：共享标签页与安全关机

## 目标

- 提高共享眼睛在线状态的可识别度。
- 把 V0.6.1 的全屏 Dialog 改成 Ubuntu 命令终端内部的只读标签页。
- 防止用户和兰儿同时使用 Ubuntu 时，任意一方误关机中断另一方任务。
- 使用独立 applicationId，让 V0.6.2 与历史验证包并存。

## 共享标签页

- 离线：眼睛为 `Color.GRAY`，背景继续使用 `tabInactiveColor`。
- 在线：眼睛为 `0xFF00E676` 亮绿色，背景不变化。
- 点击眼睛只添加一个 `兰儿共享` 虚拟标签；重复点击会选中既有标签，不重复创建。
- 共享标签与普通终端标签使用同一标签栏，可切换、可单独关闭。
- 共享内容由 Canvas 直接绘制，展示最近命令、逐块输出、错误和退出码。
- 共享标签选中时，Canvas 输入、IME、方向键、Ctrl+C、命令输入行、虚拟键盘和环境配置入口均被禁用。
- 共享数据仍只保存在内存中，不创建 PTY、不写磁盘，也不改变命令执行权限链。

## 在线参与者定义

当前架构无法从 RDC 的“桥已连接”状态判断远端聊天窗口此刻是否仍在完成一项任务；把桥连接
直接当作用户在线，会导致桥长期连接时 Ubuntu 永远无法正常关机。因此 V0.6.2 使用可验证的
运行时参与者：

1. `userInterfaceClients`：当前实际打开的 Ubuntu 命令终端界面实例；
2. `hiddenAiOperations`：当前正在执行的隐藏 Ubuntu 命令数量。

多个界面实例会分别计数；多个隐藏命令会记录操作数，但在参与者总数中归为兰儿这一类参与者。
标签页数量不等于用户数量，不参与关机判断。

## 关机规则

- 用户界面请求关机：若有隐藏 AI 操作，或另有终端界面实例，拒绝关机并提示
  `当前还有其他用户正在使用 Ubuntu，暂时无法关机。`
- `ubuntu.stop` 请求关机：若用户仍打开 Ubuntu 命令终端，拒绝关机并返回同一错误。
- 最后一个终端进程退出触发的系统清理：若隐藏 AI 操作仍在运行，拒绝关机。
- 空闲定时关机继续沿用既有 `hasActiveUbuntuWork` 检查，不改变用户配置的空闲策略。
- `prepareForMaintenance` 等应用维护流程仍可强制释放资源，避免升级或进程退出被永久阻塞。

隐藏命令的“准入检查”和显式关机检查共用 `ubuntuLifecycleMutex`：若隐藏命令先获准，关机能看到
活动计数并拒绝；若关机先进入 STOPPING，后来的隐藏命令会收到标准的 Ubuntu stopped 错误。

## Registry 规则

V0.6.2 没有增加新的执行调用名：共享状态继续使用 `ai_limbs.ubuntu.share.status`，关机继续使用
`ubuntu.stop`。两项既有 Registry 描述已同步更新，新增参与者字段和并发保护语义。以后新增真正的
调用地址，仍必须在同一次修改中完成 Dispatcher 路由与 `AiLimbsCoreCapabilityRegistry` 注册。

## 版本与构建

- `applicationIdSuffix = .ailimbs.v062`
- `versionNameSuffix = -ai-limbs-v0.6.2-build1`
- 正式构建仅由 GitHub Actions 执行，本地只做静态预检。

## 验收清单

- 隐藏 Ubuntu 命令运行时眼睛变为亮绿色，结束后恢复灰色。
- 点击眼睛新增并选中 `兰儿共享` 标签，重复点击不重复新增。
- 可在共享标签和普通 Ubuntu 标签之间切换，无需关闭弹窗。
- 共享标签无法通过触摸、软键盘、硬件方向键或底部控件写入 PTY。
- 兰儿操作期间用户关机被拒绝并显示提示。
- 用户打开 Ubuntu 命令终端时，`ubuntu.stop` 被拒绝且不改变 RUNNING 状态。
- 单一用户独占时仍可正常关机。
- V0.6.2 可与 V0.6.1 并存安装。
