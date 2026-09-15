# Step 5 · Host attach-only / UI Shell

本阶段解决 Android Host 在 Resident Core 已接管 Plugin Kernel 后的重启行为。目标不是完成跨进程插件 UI，而是先保证 Host 冷启动、Activity 重建或系统组件拉起进程时，不会恢复第二套业务运行时。

## 启动角色选择

`ResidentHostRuntimeResolver` 在 `OperitApplication.initializeMainApplication()` 的业务初始化之前读取 Core 状态和 takeover fence，并把当前 Host 固定为以下四种进程角色之一：

- `LEGACY_HOST`：Core 未持有业务，且不存在 takeover fence。保留当前兼容启动路径。
- `UI_PROXY_ATTACHED`：同版本 Core 已处于 `business_phase=running`、`runtime_owner=resident_core`，且 Plugin Kernel 已启动。Host 只作为 UI shell。
- `UI_PROXY_PENDING`：handoff 已 armed，Core 正等待 Host 退出或正在获取 owner / 启动 Kernel / claim backend。Host 不得回退为 LEGACY_HOST。
- `UI_PROXY_BLOCKED`：takeover fence 存在但 Core 不可达、build 不匹配、业务快照不一致或 takeover 已失败。该状态故意拒绝静默 fallback。

一旦某个 Host 进程进入 UI_PROXY，它不能在同一进程内降级回 LEGACY_HOST。后续组件或 Activity 再次调用初始化时，只允许刷新同一 Core session 的 `PENDING -> ATTACHED` / 诊断状态。Resident OFF 的显式角色切换留给总状态机阶段完成。

## UI shell 边界

UI_PROXY 进程初始化 `PluginHostUiProxyRuntime`，它只拥有 UI 侧 registry / presentation state：

- `PluginUiRegistry`
- `SystemPluginUiRegistry(UI_PROXY)`
- `DynamicNavigationSurfaceRegistry`
- `PluginPagePresentationRegistry`

它没有 PluginManager、runtime adapters、ChildExtensionRuntime、capability registry、Bridge、Dispatcher，也不会取得 `plugin_kernel` lease。`PluginPlatformKernel` 的四个 UI registry getter 在 Kernel 未初始化且 UI proxy 已 attach 时只返回这些 Host-shell registry；其他业务 getter 继续要求真实 Kernel。

这些 UI registry 当前只是安全空壳。Step 9 才会通过明确的状态 / 事件契约把 Core 的插件 UI 快照同步回来；本阶段不允许为了恢复插件页面而在 Host 再 mount 一份插件。

## 业务启动抑制

当 Host 处于 UI_PROXY：

1. `OperitApplication` 在 ActivityLifecycleManager 和基础 UI / preference 初始化后直接结束主业务初始化，因此不会执行 AIMessageManager / LanerChatPlugin 注册、Plugin Kernel restore、AppLifecycle plugin dispatch、AIForegroundService 自动启动、工具注册、Workflow / accessibility 等后续业务启动链。
2. `AIForegroundService` 若被 Android 冷启动，会在业务对象创建前检测 UI_PROXY 并 `stopSelf()`。
3. `FloatingChatService` 同样拒绝在 UI shell 中启动自己的业务 runtime。
4. `MainActivity.startPluginLoading()` 在 UI_PROXY 下不启动 MCP / plugin loading。

Activity 和普通 Host UI 仍可创建；插件 UI registry 可以安全被 Compose 读取，但在 Step 9 接入 Core 快照以前不会恢复实际插件页面。Android component 的真实跨进程代理协议也仍属于后续阶段。

## 不变量

- Core 业务 owner 存在时，Host 不调用 `PluginPlatformKernel.initialize()/start()`。
- takeover pending / failed / Core 暂时不可达且 fence 存在时，Host 也不能恢复 LEGACY_HOST。
- UI_PROXY 不持有 `plugin_kernel` lease，不 mount plugin runtime，不注册 Bridge / Dispatcher / capability 业务 owner。
- `PluginPlatformKernel.isInitialized/isStarted` 在 UI shell 中保持 false；UI registry fallback 不伪装成业务 Kernel。
- build25 的 LocalSocket connect/timeout 顺序与 Guardian singleton lease 继续保持。

## 本阶段没有完成

- Bridge / Dispatcher / Interaction Cycle 仍未迁入 Core。
- 普通插件、子插件、Ubuntu 仍未恢复到 Core。
- 插件 View / Compose / ActivityResult 的跨进程状态与事件代理尚未实现。
- Resident ON/OFF 尚未调用 one-way handoff，也没有完成显式退出 / 恢复状态机。
- CPU、网络、冻结 / LEV 与长锁屏验收尚未进入本阶段。

本阶段只做源码迁移，不编译、不安装、不晋升 Current。
