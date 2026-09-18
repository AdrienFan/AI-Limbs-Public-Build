---
title: Host Primitive Execution Affinity Contract
status: MODELED_PARTIALLY_ENFORCED
scope: AI Limbs Base / HostPrimitiveGatewayBindings
---

# Host Primitive Execution Affinity Contract

## 目的

本文件定义 HostPrimitiveGatewayBindings 中全部 Host Primitive 的执行归属。

本契约已写入 Kotlin 数据模型，但不改变当前 runtime route。现有 HOST_TOOL、KERNEL、COMPONENT_PROXY 等 route kind 继续保持当前行为；后续阶段才会让执行器读取 affinity 并据此路由。

核心规则：

- Business Owner 在任一时刻只能是 LEGACY_HOST 或 Resident Core 之一。
- Android presentation 和依赖 Host 进程身份的 framework 对象由 Android Host 持有。
- Application Context、Binder system service、文件、网络、受控 Shell 等不依赖 Host 进程本地 UI 状态的能力可以由 Resident Core 执行。
- 由 Host Service、Activity、View、MediaProjection 或其它 Host-only 对象产生的进程本地状态，不得在 Core 中读取另一份静态副本。
- 每个 primitive 必须声明一个 primitive-level execution affinity。
- CROSS_PROCESS_BACKEND 必须同时声明 operation-level ownership，不能成为未决定归属的逃生口。
- UNBOUND primitive 在真正绑定前必须先重新声明为非 UNBOUND affinity。
- 新 primitive 或新 operation 必须先定 execution affinity，再定义 ABI，再实现 binding，禁止先实现、后补 proxy。

## Affinity 定义

| Affinity | 定义 | Resident 目标位置 |
| --- | --- | --- |
| CORE_SAFE | 不依赖 Host 进程本地 UI、Activity、Window、Host Service state，由 Business owner 执行 | Resident Core |
| HOST_FRAMEWORK | 依赖 Activity、Intent 交互、MediaProjection、Window/Activity token、runtime permission UI 或 Host 生命周期控制 | Android Host |
| HOST_UI | 直接持有或操作 View、Compose、WebView、overlay、导航 presentation 或其它可视 UI state | Android Host |
| HOST_SERVICE | 权威状态来自 Android Host 中的 Service 或其进程本地 service singleton | Android Host |
| CROSS_PROCESS_BACKEND | 协议天然跨 Core、Host 或外部 backend，单进程不能完整拥有 | 按 operation profile 分工 |
| UNBOUND | 当前没有 runtime binding，不提前猜测未来执行位置 | 不可调用 |

HOST_SERVICE 当前没有一个完整 primitive 可以单独归入。现有涉及 Host Service 的能力都与 Core 业务混合，因此归入 CROSS_PROCESS_BACKEND。

## Primitive 归属总表

| Primitive | Affinity | 当前状态 | 归属说明 |
| --- | --- | --- | --- |
| host.filesystem@1 | CROSS_PROCESS_BACKEND | 部分绑定 | 数据操作可 Core；open/share 需要 Host FileProvider + Intent + Activity |
| host.process@1 | CORE_SAFE | 仅 execute 绑定 | execute_shell 走 Shell backend；其余 session operations 仍 UNBOUND |
| host.ui.automation@1 | CROSS_PROCESS_BACKEND | 已绑定 | Core 持有语义与授权；实际 backend 混合 Accessibility、Shell 与 Host UI |
| host.screen.capture@1 | HOST_FRAMEWORK | 已绑定 | MediaProjectionHolder、ScreenCaptureActivity、MediaProjection token 具有 Host 进程亲和性 |
| host.network@1 | CORE_SAFE | 部分绑定 | HTTP/Multipart/Cookie 与 TCP listener snapshot 可由 Core 执行；listen 仍 UNBOUND |
| host.background.runtime@1 | UNBOUND | 未绑定 | 实现前重新声明，不在本阶段预判 |
| host.notification@1 | CROSS_PROCESS_BACKEND | 已绑定 | publish 可 Core；observe 权威数据来自 Host NotificationListenerService |
| host.android.settings@1 | CROSS_PROCESS_BACKEND | 已绑定 | get 可 Core；set 的授权/Settings Activity 交互必须 Host |
| host.android.package@1 | CROSS_PROCESS_BACKEND | 已绑定 | list/stop 可 Core；install/uninstall/launch 需要 Host Activity/Intent |
| host.bluetooth@1 | CROSS_PROCESS_BACKEND | 已绑定 | session/backend 可由 Business owner 持有；runtime permission 与 enable dialog 必须 Host |
| host.location@1 | CORE_SAFE | 已绑定 | LocationManager 与 UID permission 不依赖 Host UI |
| host.clipboard@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.permission@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.audio.capture@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.audio.playback@1 | CORE_SAFE | 已绑定 | ExoPlayer 与播放状态由 Business owner 持有；Resident 下由 Core 持有 |
| host.android.component@1 | HOST_FRAMEWORK | 已绑定 | Activity、broadcast、activity result、window lease/flags 属于 Host framework |
| host.event@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.device.state@1 | CORE_SAFE | 已绑定 | 设备只读信息由 application/system APIs 获取 |
| host.scheduler@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.ai.inference@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.chat@1 | CORE_SAFE | 已绑定 | Chat history、ChatRuntime、AI request/stream 全部由唯一 Business owner 执行；FloatingChatService 生命周期不属于该 primitive |
| host.logging@1 | CORE_SAFE | 已绑定 | 日志文件、PluginStore、child snapshot 与 MediaStore export 由 Business owner 执行 |
| host.secrets@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.ui.surface@1 | CROSS_PROCESS_BACKEND | 已绑定 | surface/screen registry 属于 Core；真正 open route 的 Activity launch 属于 Host |
| host.window.overlay@1 | UNBOUND | 未绑定 | 实现前重新声明；若直接持有 overlay/View，应声明 HOST_UI |
| host.capability@1 | CORE_SAFE | 已绑定 | Capability search/describe/invoke 属于 Business/Dispatcher |
| host.plugin.service@1 | CORE_SAFE | 已绑定 | Plugin service registry 与 endpoint 属于 Plugin Kernel Business owner |
| host.extension.routing@1 | CORE_SAFE | 已绑定 | Extension point/router/binding 属于 Plugin Kernel Business owner |
| host.plugin.runtime@1 | CORE_SAFE | 已绑定 | mount/stop/status/list 属于 Plugin Kernel Business owner |
| host.pipeline.hook@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.android.usage@1 | CROSS_PROCESS_BACKEND | 已绑定 | UsageStats query 可 Core；缺授权时 Settings Activity 必须 Host |
| host.content@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.web.runtime@1 | HOST_UI | 已绑定 | WebView、View/ViewGroup、Compose/overlay、MotionEvent、MainLooper、browser session map 均属于 Host UI |
| host.ingress@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.authorization@1 | CORE_SAFE | 已绑定 | Policy describe/evaluate 属于 Core policy/business plane |
| kernel.plugin.trust@1 | CORE_SAFE | 已绑定 | Trust keyring 与 package/signature verification 属于 Kernel security plane |
| host.ui.widget@1 | UNBOUND | 未绑定 | 实现前重新声明；若直接注册 Android UI，应声明 HOST_UI |
| host.camera.capture@1 | UNBOUND | 未绑定 | 实现前重新声明 |
| host.custom_access_prompt@1 | CORE_SAFE | 已绑定 | Managed document 属于 Business/policy data |
| host.work_manual@1 | CORE_SAFE | 已绑定 | Managed document 属于 Business/policy data |
| host.privileged.runtime@1 | CROSS_PROCESS_BACKEND | 已绑定 | control state 属于 Business owner；权限 backend 是独立 Binder process，并已有 Host→Core handoff |
| host.resident.runtime@1 | HOST_FRAMEWORK | 已绑定 | Resident enable/start/stop/core lifecycle 是 Host/Core 进程所有权编排 |
| host.ui.layout@1 | HOST_UI | 已绑定 | ToolboxLayoutController、edit session、route navigation 属于 Host presentation |
| host.interaction.cycle@1 | CORE_SAFE | 已绑定 | Interaction Cycle、gate、timeout、policy generation 属于 Business/policy owner |

归属数量：

| Affinity | Primitive 数量 |
| --- | ---: |
| CORE_SAFE | 16 |
| HOST_FRAMEWORK | 3 |
| HOST_UI | 2 |
| HOST_SERVICE | 0 |
| CROSS_PROCESS_BACKEND | 9 |
| UNBOUND | 14 |
| 合计 | 44 |

## CROSS_PROCESS_BACKEND operation profile

### host.filesystem@1

- CORE_SAFE: list, read, read_range, read_full, read_binary, write, write_binary, delete, move, copy, mkdir, stat, find, grep
- HOST_FRAMEWORK: open, share

Linux/SystemEnvironment delegation 仍由业务能力自身处理；这里只约束 Android Host interaction。

### host.ui.automation@1

snapshot, click, tap, long_press, set_text, key, swipe 均保留 CROSS_PROCESS_BACKEND。

目标边界：

- Core 持有 authorization、operation semantics、backend selection
- Accessibility backend 通过明确 Binder/service boundary
- Debugger/Root backend 使用受控 Shell
- Activity、overlay、window、process-local UI state 相关部分必须 Host
- 跨边界只传 neutral data，不传 View、AccessibilityNodeInfo 或其它进程本地对象

### host.notification@1

- publish → CORE_SAFE
- observe → HOST_SERVICE

observe 的权威数据来自 Host 进程内 OperitNotificationListenerService → OperitNotificationStore，不得在 Core 读取另一份静态 Store。

### host.android.settings@1

- get → CORE_SAFE
- set → CROSS_PROCESS_BACKEND

set 的 ContentResolver 写入可以由 Core 发起；涉及 ACTION_MANAGE_WRITE_SETTINGS 的用户交互必须切到 Host，Core 不得直接 startActivity。

### host.android.package@1

- list, stop → CORE_SAFE
- install, uninstall, launch → HOST_FRAMEWORK

### host.bluetooth@1

- permission, enable → HOST_FRAMEWORK
- state, bonded → CORE_SAFE
- scan, connect, listen, accept, send, read, transact, close, ble_connect, ble_discover, ble_read, ble_write, ble_transact, ble_subscribe, ble_notifications → CORE_SAFE with Business-owner session state

Bluetooth session state 必须属于唯一 Business owner。Host→Core 或 Core→Host transition 可以关闭并重建 active session，除非未来显式定义 handoff；禁止两个进程同时维护同一 session id。

### host.chat@1

整个 primitive 现在声明为 CORE_SAFE。

- create, list, find, switch, title, delete, messages, messages_range, send, stream → CORE_SAFE
- create/switch/send/stream 统一从当前 Business Owner 进程的 ChatRuntimeHolder 获取 ChatServiceCore
- list/find/title/delete/messages/messages_range 直接使用 Business-side chat data/repository
- FloatingChatService、浮窗 visibility、Host service bind/start/stop 属于 Host presentation/service lifecycle，不是 host.chat@1 ABI 的一部分
- Resident UI_PROXY 下 FloatingChatService 已有硬保护：不得创建 ChatRuntimeHolder/ChatServiceCore，启动后立即 stopSelf
- host.chat@1 不得通过 LocalBinder、bindService、startService 或 FloatingChatService.getInstance() 获取或重建业务 runtime
- 所有 10 个 host.chat@1 operation 入口都有 Business Owner guard；Resident UI_PROXY 连 chat data 读写也不得执行，chatPrimitiveCore 进一步保证不得在 Host 创建 ChatRuntimeHolder/ChatServiceCore

### host.ui.surface@1

- list, register, remove → CORE_SAFE
- open → CROSS_PROCESS_BACKEND

open 由 Core 解析 surface_id/screen_id 与 route metadata，Android Host 执行 MainActivity navigation。

### host.android.usage@1

query → CROSS_PROCESS_BACKEND。

UsageStats 数据查询可由 Core 完成；缺少 Usage Access 时，打开系统 Settings 页面必须由 Host 完成，不能使用 Core.startActivity 作为权限引导路径。

### host.privileged.runtime@1

status, pair, prepare, stop, select 均属于 CROSS_PROCESS_BACKEND。

- AI Limbs 业务侧持有 backend selection、launch token、authorization state
- 权限 server 是独立 Binder process
- Host→Core takeover 继续使用 verified Binder handoff/adoption
- raw IBinder 永远不进入 plugin ABI
- Host 与 Core 不得同时持有两个权威 backend identity

## HOST_FRAMEWORK 与 HOST_UI 判定边界

出现下列任一事实，默认不得声明 CORE_SAFE：

- 需要 Activity、Activity Result、runtime permission dialog
- 需要 Window、window token、overlay attachment
- 需要 Host 进程中的 MediaProjection token
- 需要 View、ComposeView、WebView、MotionEvent
- 依赖 Host Service 填充的进程本地 singleton/store
- 通过 startActivity 才能完成 operation 的正常控制流
- 依赖 FloatingChatService、MainActivity 或其它 UI shell lifecycle

以下事实本身不要求 Host ownership：

- Application Context
- ContentResolver 的无交互读写
- PackageManager 的只读查询
- Android system service Binder，只要不依赖 Host process-local state
- 网络、文件、受控 Shell
- Plugin Kernel、Dispatcher、Policy、Trust、managed document
- 由唯一 Business owner 有意持有的纯进程本地 state

## 新能力准入规则

从本契约开始，新 primitive 或新增 operation 的设计顺序必须是：

1. 先声明 primitive-level execution affinity
2. CROSS_PROCESS_BACKEND 同时提交 operation-level ownership profile
3. 再定义 ABI payload，跨进程只允许 neutral data
4. 再实现 runtime binding
5. 最后才允许注册进 HostPrimitiveGatewayBindings

禁止以下顺序：

先注册工具 → 在当前进程直接调用 → Resident 失败后再补 proxy

UNBOUND 不是最终实现状态。operation 从 UNBOUND 变成 callable 的同一次变更中，必须先完成 execution affinity 声明。

## 已开始按 affinity 执行的能力

- host.screen.capture@1/capture：Resident Core 只发送 neutral JSON request；MediaProjection、ScreenCaptureActivity 与 process-local capture state 全部留在 Android Host；Host 返回 success/path/error
- host.ui.automation@1：Core 保留授权、operation 语义和唯一 backend 选择；Android Host 只持有 FloatingChatService/UIOperationOverlay 等 presentation state；Accessibility 通过独立 AIDL provider，Debugger/ADMIN-compat 通过显式 Debugger-family shell executor，Root 通过显式 Root shell executor
- host.chat@1：全部 10 个 operation 均为 CORE_SAFE；create/switch/send/stream 只访问当前 Business Owner 的 ChatRuntimeHolder，Host FloatingChatService 生命周期完全退出 primitive ABI

## 与当前实现的差异

本文件是目标归属契约，不表示当前 runtime 已经遵守。

当前已知差异包括：

- host.notification@1/observe 仍在 Core 读取进程本地空 Store
- host.web.runtime@1 仍在 Core 创建和操作 WebView/UI state
- host.filesystem@1/open|share 仍可在 Core startActivity
- host.android.package@1/install|uninstall|launch 仍可在 Core startActivity
- host.android.settings@1/set 与 host.android.usage@1/query 的权限引导仍可从 Core 发起 Activity
- host.resident.runtime@1 仍可由 Resident Business execution path 进入自身 lifecycle controller

当前 HostPrimitiveGatewayBindings 已同时保存 primitive-level affinity 与 operation-level affinity；SystemHostPrimitiveExecutor 仍只读取 route kind，不读取 affinity。下一阶段才能开始让具体能力按 affinity 修正执行位置。
