# AI Limbs 0.8 — System Peripheral Host Boundary

## 目标

AI Limbs 0.8 的第一阶段不直接引入“系统环境中心 + Ubuntu/Windows 子插件”。
当前 Ubuntu 插件先同时承担“主机机箱 + Ubuntu 系统”职责。
Base 面向该系统环境只保留通用宿主能力，不再拥有 Ubuntu 业务语义。

第一阶段的核心边界：

- Power：为系统环境提供持续运行所需的宿主生命周期与后台生存租约。
- Display：为系统环境提供 AI Limbs 内部的显示页面、路由和受控 UI 容器。
- Input：为系统环境提供统一的键盘、鼠标/指针与触摸输入通道。

## 当前 Host Primitive 盘点

### Display 候选：`host.ui.surface@1`

状态：CONFIRMED / BOUND。
当前操作：`list`、`register`、`open`、`remove`。
它负责页面、route、screen 与 surface 生命周期，已经具备第一阶段“显示器外壳”的主体能力。

限制：当前主要面向声明式 UI / Plugin Screen，并不是通用 framebuffer、SurfaceView 或视频帧传输协议。
Ubuntu 第一阶段可以直接复用；未来 Winlator 接入前需要重新评估是否升级 Display v2。

### Power 候选：`host.background.runtime@1`

状态：CONFIRMED / DECLARED，目前未绑定。
声明操作：`acquire_lease`、`update_lease`、`release_lease`、`status`。
设计语义本身正好对应“供电”：前台服务、WakeLock、恢复和后台生存租约由 Host 负责，系统环境内部业务由插件负责。

0.8 第一阶段应优先把它真正绑定，而不是新造 Ubuntu 专用电源接口。

### Input：新增通用 `host.input@1`

当前 Host Primitive Catalog 没有适合“系统环境外设输入”的独立原语。
现有 `host.ui.automation@1` 面向 Android Accessibility/Shower，控制的是宿主 Android UI，不能充当 Ubuntu/Winlator 的键盘鼠标。

0.8 第一阶段新增统一输入原语，键盘与鼠标共享同一显示/环境会话路由。
建议操作语义包括：`keyboard_text`、`keyboard_key`、`keyboard_modifier`、`pointer_move`、`pointer_button`、`pointer_scroll`、`pointer_tap`。
Ubuntu 第一阶段只实现终端实际需要的文本、按键与组合键；Winlator 接入时再使用完整 pointer 能力。
输入必须绑定明确的 system/display session，不能退化成对 Android 全局界面的任意注入。

### `host.plugin.runtime@1` 不是电源

它已 BOUND，但 `mount/stop` 管的是整个插件 Runtime 的启用和禁用。
如果用它当“关机键”，会连机箱 UI、显示器和电源按钮一起卸载，因此职责错误。
它继续属于 Plugin Kernel，不进入系统环境的电源语义。

## 辅助但不属于“电源/显示器/输入本体”的原语

- `host.screen.capture@1`：宿主屏幕/显示目标截图，偏传感器与读回。
- `host.event@1`：生命周期、屏幕、电源、网络、内存等宿主事件。
- `host.device.state@1`：设备显示、电池、电源、网络等只读状态。
- `host.authorization@1`：授权平面。
- `host.plugin.runtime@1`：插件装载与隔离。

## 必须从 Base 迁出的 Ubuntu 语义

### 直接删除/迁移目标

- `host.ubuntu.runtime@1`
- `ubuntu.status`
- `ubuntu.start`
- `ubuntu.stop`
- `ubuntu.idle.get`
- `ubuntu.idle.set`
- `UbuntuRuntimeState` / `UbuntuIdlePolicy`
- `TerminalManager` 与 Ubuntu rootfs/bootstrap/PTY/native 实现
- Ubuntu 首次预装与环境配置逻辑

### `host.process@1` 需要拆污染，不宜整体删除

`host.process@1` 作为通用进程宿主概念可以保留。
但当前的 `create_session`、`session_input`、`session_screen`、`hidden_execute` 等操作直接依赖 Terminal/Ubuntu 实现。
0.8 应把 Ubuntu PTY/Terminal Session 所有权迁入 Ubuntu 插件，只给 Base 留真正通用的进程后端。

当前 Base 中直接 import terminal 模块的文件共有 7 个，属于迁移审计重点。

直接 import `com.ai.assistance.operit.terminal` 的 Base 文件：

1. `StandardFileSystemTools.kt`
2. `StandardTerminalCommandExecutor.kt`
3. `OperitTerminalManager.kt`
4. `Terminal.kt`
5. `MarkdownCodeTypeface.kt`
6. `ComputerScreen.kt`
7. `CanvasCodeEditorView.kt`

## 0.8 第一阶段顺序

1. 绑定 `host.background.runtime@1`，把它正式作为系统环境的 Power lease。
2. 保留并验证 `host.ui.surface@1` 作为 Ubuntu 阶段的 Display 外壳。
3. 新增并绑定 `host.input@1`，作为系统环境的 Keyboard / Pointer / Touch 输入外设。
4. 补齐 android_inprocess 插件 native library 装载能力。
5. 把 Terminal Core / PTY / rootfs / bootstrap / Ubuntu lifecycle 迁进 Ubuntu 插件。
6. 清除 Base 的 `host.ubuntu.runtime@1` 与 `ubuntu.*` 业务能力。
7. 重新审计 `host.process@1`，只保留真正与具体系统无关的进程能力。

等上述边界稳定后，再把当前 Ubuntu 插件拆成“系统环境中心（机箱） + Ubuntu 子插件”，并引入 Winlator/Windows 子插件。
