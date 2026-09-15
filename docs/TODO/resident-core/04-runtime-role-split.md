# 业务运行时 / 界面运行时拆分

状态：Step 2 源码边界已建立；未编译、未部署、未切换 Resident ON/OFF。

## 目标

Resident Core 最终只承载 BUSINESS；Android Host 最终只承载 UI_PROXY。迁移期间保留 `LEGACY_HOST` 作为当前单进程兼容模式，直到后续步骤真正接通 Core 接管与 Host attach。`LEGACY_HOST` 不是最终架构，也不得在 Core 已成为业务 owner 后作为静默 fallback。

角色定义：

- `BUSINESS`：Plugin Kernel、PluginManager、插件/子插件业务生命周期、providers/services/capabilities、Extension Router、能力注册与后续迁入的 Bridge / Dispatcher / Interaction Cycle 权威状态。
- `UI_PROXY`：Android Activity、View / Compose、ActivityResult、window token、页面 renderer/accessory/slot 以及 Host 侧展示会话。UI_PROXY 不得挂载 PluginManager 或第二套插件业务运行时。
- `LEGACY_HOST`：当前 Host 的兼容角色，暂时同时承载业务和 UI；仅用于迁移前的现有启动路径。

## 当前代码分类

可以留在 BUSINESS 的对象：

- `PluginManager`、`PluginContributionRegistry`、`PluginHostCapabilityRegistry`、Extension Point / Router、providers/services/capabilities。
- `ChildExtensionRuntime` 的生命周期、能力、AI ingress discovery 和业务 binding。
- `PluginUiRegistry` 中的 HomeTile / Screen / Theme 元数据；`InProcessScreen` 是 `schemaId + documentJson`，本身不是 Android View。
- `InProcessUiStateProvider` / `InProcessUiContributionProvider` 的 JSON 状态与事件语义可以作为未来代理协议的业务侧来源，但对象引用本身不能跨进程直接传递。

必须留在 Host/UI_PROXY 的对象：

- `SystemUiPageV1` 及 Plugin Center 的 Compose 页面。
- `SystemPluginUiRendererV2`、`SystemPageAccessoryRendererV1`、`SystemPageSlotRendererV1`。
- `InProcessPageProvider.createView()`、`InProcessSharedUiHost.createComponent()` 返回的 Android `View`。
- ActivityResult、window token、Activity/Compose lifecycle，以及任何真实 `ComposeView` / Android View 实例。
- `SystemPluginUiRegistry` 中保存的 renderer/page 对象。

## 当前插件现场梳理

当前插件源码已经存在直接 UI provider，不能把这些对象当 IPC 数据搬入 Core：

- System Environment Center：`SystemEnvironmentCenterPageProvider`，直接创建 `ComposeView`。
- Laner Access Manager：`LanerAccessManagerPageProvider`。
- Log Center：`LogCenterPageProvider`。
- UI Editor：`UiEditorPageProvider`。
- Permission Service：`PermissionPage`。
- Ubuntu System Child：`UbuntuSubsystemPageProvider` 和 `SystemEnvironmentConfigurableDisplayAdapter` 直接返回 Android `View`；其 `UbuntuChildHostAdapter` 在业务 mount 时会建立插件资源 Context，但真实 View 只应由 Host UI 路径请求。
- Plugin Center：当前 `PluginCenterEntry` 同一个 entry 同时注册 delegated gateway/services 与 `SystemUiPageV1`/三个 Compose renderer，因此 BUSINESS Core **不能直接 mount 当前 Plugin Center entry**。后续必须由 Host UI proxy 与 Core 业务控制面分离，而不是在两个进程各 mount 一份 Plugin Center。

## 本 Step 的源码护栏

1. `PluginRuntimeRole` 明确为 `LEGACY_HOST / BUSINESS / UI_PROXY`。
2. `PluginRuntimeHost(UI_PROXY)` 会拒绝 mount 普通插件业务 runtime。
3. `PluginPlatformKernel` 允许 `LEGACY_HOST` 或 `BUSINESS`；明确拒绝 `UI_PROXY` 初始化。
4. `BUSINESS` 启动 Plugin Kernel 时不 restore 当前 UI-bearing `SystemPluginController` / Plugin Center entry。普通插件和子插件业务 runtime 仍可按现有路径恢复。
5. `SystemPluginUiRegistry(BUSINESS)` 拒绝注册 Toolbox page、plugin surface renderer、page accessory 和 page slot renderer，防止 Core 保存 Compose/View UI 对象。
6. `android_inprocess` 的 `createPluginContext()` 在 BUSINESS 中显式拒绝，避免 Core 通过插件页面路径创建 Android UI Context；`createRuntimeContext()` 与 child resource Context 仍保留给业务代码/资源装载，但它们不是 View/Compose 的执行许可。
7. `PluginHostUiProxyRuntime` 是 Host UI 半边的最小骨架，只持有 UI object registry / presentation state，不含 PluginManager、ChildExtensionRuntime、capability registry、Bridge 或 Dispatcher。
8. 当前默认 `OperitApplication -> PluginPlatformKernel.initialize()` 不传 role，仍进入 `LEGACY_HOST`，因此本 Step 不提前切换真机行为。Core 的 BUSINESS 启动点和 Host attach-only 会在后续步骤接线。

## 尚未解决 / 后续步骤

- 普通插件的 `InProcessPageProvider`、Ubuntu display adapter 和 child UI contribution 仍是进程内对象；Step 2 只禁止 BUSINESS 挂载 System Plugin UI 与执行 Host UI 注册，没有实现跨进程 UI snapshot/event proxy。
- Plugin Center 当前 entry 仍把 UI 与服务发布绑在一次 mount 中；本 Step 通过 BUSINESS 不 mount 它避免双运行时，后续需给 Host UI_PROXY 一个不恢复业务副作用的 UI 路径，并让业务控制面由 Core 权威提供。
- Host 当前 UI 仍直接读取 `PluginPlatformKernel.*Registry`；真正替换为 Core snapshot / event proxy 属于后续 Host attach 与 UI Proxy 步骤。
- 浏览器、屏幕捕获等基座自身 Android UI 工具不属于“插件页面 runtime”本 Step 的迁移范围；它们在 Dispatcher/Host component proxy 阶段单独处理。

## Step 2 完成口径

源码层已经存在明确角色和拒绝边界：Core 可选择 BUSINESS 初始化 Plugin Kernel 且不 mount Compose-bearing Plugin Center；Host UI_PROXY 有独立无业务 runtime 骨架；当前 LEGACY_HOST 行为保持不变。这里的“可”是架构/源码级，不代表已经完成 Core 启动、IPC UI 代理、编译或真机验证。
