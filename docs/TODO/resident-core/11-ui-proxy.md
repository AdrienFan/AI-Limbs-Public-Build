# Step 9 · Core ↔ Host UI Proxy 与 Android Component Proxy

状态：源码代理边界与 Host presentation 封板加固已完成；未编译、未部署、未进行 Host 重建 / ActivityResult / 插件页面 / 真机交互验收。

## 本阶段目标

Step 8 已把普通 Parent/Child Plugin、Capability Registry 与 Ubuntu 业务控制面迁入 Resident Core。本阶段不把业务搬回 Host，而是让 Android Host 在 `UI_PROXY` 角色下重新显示 Core-owned 插件状态并把用户动作送回 Core。

当前边界为：

`Resident Core = authoritative business state + UI descriptor/state owner`

`Android Host = mirrored presentation state + Compose/View renderer + Android framework executor`

Host 重建不恢复 `PluginManager`、Parent/Child business runtime、Capability Registry、Dispatcher、Bridge 或 Ubuntu runtime；它只重新 attach 到当前 Core session、抓取中性快照并挂载 Plugin Center 的 UI-only renderer。

## 独立 UI 数据面

新增 `ResidentUiProxyWire`，与原 8 KiB 的 `ResidentCoreWire` 诊断/生命周期协议分离。UI 数据面使用有界 JSON frame，并绑定当前 Core session 与同 UID / peer PID 身份。

协议 v1 当前支持：

- `snapshot`：Host 首次 attach / 重连时获取完整 Core UI 快照；
- `events`：Host 携带 revision 轮询；Core 状态变化时返回新的完整快照；
- `command`：Host 用户动作回到 Core；
- `component_poll` / `component_result`：Core 与 Host Android Component Proxy 的 rendezvous。

`events` 当前是 revision + snapshot 的 fail-soft polling，不是假装存在跨进程 Java callback。这样 Host 进程消失时 Core 不持有 Host callback/Binder wrapper，也不会因为一个失效 callback 阻塞业务 owner。

Host client 遇到瞬时 LocalSocket 失败不会退出 UI proxy；它保留最后一份内存镜像，750 ms 后重新 attach，并用完整 snapshot 校正 revision。Core session 变化仍由 session 校验 fail closed，不允许静默接到另一个 owner。

封板加固后，Host attachment 不只绑定 Core `session_id`，还绑定 Host 进程随机 `host_instance_id` 与 Core 单调分配的 `host_generation`。新 Host attach 会 retire 上一个 Host instance；旧 Host 不能重新 attach，也不能继续发送 command / component result。这样同一个 Core session 内也不会出现旧 Host 与新 Host 同时操作 presentation 的 ABA 竞态。

UI proxy server 的 accept 与单连接处理已拆开并发执行。原因是 Core command 可能同步等待 Host component result；若 server 串行处理 socket，`component_poll` / `component_result` 会被正在等待它们的 command 自己堵住，形成协议自锁。并发 client handler 允许业务 command 等待期间 Host 继续 poll / 回传 component result。

## Core-owned UI descriptor / state

Step 8 为了先完成业务迁移，曾把较多 presentation contribution 暂时抑制。Step 9 对这条边界做精确拆分：

可以留在 Core、再以 JSON 镜像给 Host 的内容：

- `InProcessScreen`：owner / id / title / description / schema id / opaque document JSON；
- Home Tile：owner / id / title / description / screen id；
- Theme 的纯数据 spec；
- `InProcessUiStateProvider`：对象本体留 Core，只导出 `stateJson`，`perform(event)` 通过 command 回到原 owner；
- Child UI contribution：provider 本体留 Core，只导出 `documentJson`，事件回到 Core provider；
- Page presentation request；
- Dynamic navigation surface / binding；
- Child snapshot / backup snapshot；
- Plugin Center 需要的 Host Primitive descriptor / policy state。

仍然禁止进入 Core presentation 的内容：

- `InProcessPageProvider.createView(Context, ...)`；
- Android `View`；
- Compose object / lambda / composition；
- Activity / Context；
- `ActivityResultLauncher` / ActivityResultRegistry object；
- Window / window token；
- IBinder / Binder wrapper；
- 任意 Parcelable 或普通 Java object 作为跨进程 payload。

`InProcessPageProvider` 因为 ABI 本身就是 `Context -> View`，在 BUSINESS Core 中继续硬抑制；本阶段没有伪装成“可序列化 View”。需要跨进程显示的插件 UI 必须走声明式 screen/document 或 JSON state/event contract。

## Host 只建立镜像，不取得 registration ownership

`PluginUiRegistry`、`PluginPagePresentationRegistry` 与 `DynamicNavigationSurfaceRegistry` 增加 UI_PROXY mirror replacement。它们直接替换 Host 内存 flow，不调用正常的业务 `register*()` 路径，也不创建插件 registration owner。

Dynamic Navigation 的 proxy replacement 不写 Host SharedPreferences；权威 surface/binding 仍来自 Core。Host 镜像被进程杀死后可以全部丢失，重新 attach 后由 Core snapshot 重建。

## Plugin Center 只在 Host 运行 UI renderer

普通插件 declarative document 的 Compose renderer 本来由 Plugin Center 提供，因此 UI_PROXY 允许 `SystemPluginController` 在 Host 恢复 Plugin Center 的 UI 包。这个 mount 只承担 renderer / toolbox Composable / Host-local self-maintenance。

Plugin Center 在 UI_PROXY 中拿到的是 Core RPC facade：

- `pluginAdmin` / `adminSecurity` / `navigation` -> Core command；
- delegated capability -> Core；
- provider directory -> Core state provider proxy；
- child control / child snapshots / child UI contribution -> Core；
- Host Primitive descriptor 与 invocation -> Core；
- `SystemUiHostV2` -> Host-local renderer registration。

Plugin Center 在 Host 发布的 presentation helper service 不发布进 Core，也不建立第二份业务 service owner。Plugin Center 自身升级/修复属于 Host renderer 包维护，因此 `selfMaintenance` 保留 Host-local。

## 用户动作必须回 Core

`PluginDeclarativeScreen` 在 UI_PROXY 中不再直接访问 `PluginPlatformKernel.manager` 或本地 capability registry。按钮动作携带 Host 当前 screen 的 trusted owner / screen id / capability id / JSON parameters 回到 Core。

Core 再次验证：

1. screen 当前存在；
2. screen owner 与请求 owner 一致；
3. capability 确实属于该 owner；
4. active authorization / granted scope 仍成立；
5. 调用经过 `AiLimbsExecutionAuthorization.withExplicitUiAction`。

Renderer 不能自行提供另一个 pluginId 来冒充其他插件。UI state provider 与 child contribution event 同样由 Core 根据登记 owner / extension identity 解析，不信任 Host 自报业务对象。

## Active screen 与 page presentation

`OperitApp` 是 Host 全局路由里唯一的“当前插件页面”报告者。进入 / 离开插件 route 时，UI_PROXY 只发送 `set_active_screen` command；Core 验证 screen 后维护 authoritative active-screen identity。

沉浸模式退出等用户操作在 UI_PROXY 下也回 Core 修改 presentation request，Host 本地 registry 只接收下一次 Core snapshot。Legacy Host 模式仍保留原本地路径。

这避免具体 `PluginDeclarativeScreen` 与全局 App shell 同时上报 active screen，造成双写或 presentation lease 竞态。

## Android Component Proxy

新增 Core 侧 `ResidentHostComponentProxy` / `ResidentComponentProxyBroker`。Core 只排队版本化 JSON request；Host 轮询后使用真实 Android framework object 执行，并把中性结果回传。

当前协议种类：

- `activity_presence`；
- `start_activity`；
- `activity_result`；
- `window_lease`；
- `window_flags`。

### Intent / ActivityResult

Core 到 Host 的 Intent 只允许中性 spec：action、data URI、MIME、package、显式 component 名、categories、flags 与受限 primitive / string-array extras。Host 才创建真实 `Intent`。

`activity_result` 在 Host 使用一次性的 `ActivityResultRegistry` launcher；回 Core 的结果只保留：result code、action、data URI、MIME、flags、categories、clip-data URI，以及安全 primitive / string-list extras。Parcelable、Binder 与任意 framework object 会被丢弃。

ActivityResult 默认 rendezvous timeout 为 120 秒；普通 component request 为 15 秒。若 Host 在一个已启动的 ActivityResult 中途死亡，请求 fail closed / timeout，不自动 replay，避免重建 Host 后重复拉起相机、文件选择器或其他有副作用 Activity。用户可在新 Host 上重新触发动作。

Component request 的 claim 也绑定 `host_instance_id`。当新 Host attach 时，旧 Host 已 claim 但未完成的 request 不再一直等待原 15 / 120 秒 deadline，而是立即以 `HOST_INSTANCE_REPLACED` 结束；旧 Host 之后送回的异步 ActivityResult 会因为 instance / generation 不匹配被拒绝。Host 侧一次性 launcher 还按 Core 下发的 deadline 自动 unregister，避免 Core 已超时后 framework launcher 长期残留。

### Window

真实 `Window` 只存 Host 的 `WeakReference`。Core 只得到随机 `window_lease_id`；后续 window flag request 携带 lease id。Host 重建后旧 lease 自然失效，Core 永远拿不到 window token / Binder。

Window lease 现在还绑定取得 lease 时的真实当前 Window。若 Activity 已重建或前台 Window 已变化，旧 lease 返回 `WINDOW_LEASE_STALE`；不得在 lease 失效后 fallback 到“当前新 Activity 的 Window”，避免旧业务请求误改新页面。

## Parent / Child Host presentation 与 Ubuntu 设置

插件大仓配套提交 `c86002c` 把需要真实 Android View / Compose 的插件页面拆成 Host-only presentation entry。Core 只发布可验证的 presentation descriptor；Host 用 admitted runtime archive 装载 presentation class，但不 mount 业务 entry。System Environment Center 只接受 `kind=system_environment_presentation` 且 `extension_id == ownerPluginId`、parent / point / apiVersion 全匹配的 child presentation。

Ubuntu presentation 使用 `TerminalUiController`，Resident Host 页面不调用 `TerminalManager.getInstance()`，也不实例化 FTP / SSH / Source / Cache / chroot 业务 manager。高级设置页面抽成 `TerminalSettingsController`：字体、虚拟键盘等纯 presentation preference 留 Host；cache/reset、FTP、SSH、软件源、shared tmp、chroot 通过 child 私有 presentation-command endpoint 回到 Core。该 endpoint 不进入 AI capability catalog，并跟 `ActiveChild` owner 一起 pin / retire；partial mount 与 stop cleanup 仍遵守 Step 8 fail-closed 规则。

SSH 配置日志同时做了脱敏：不再打印完整 JSON / 密码 / private-key passphrase；并补齐原先遗漏的端口转发与 KeepAlive 持久化字段。

## 本阶段没有声称完成的内容

- 新 Component Proxy 协议与 Core gateway 已建立，但历史 `host.android.component@1`、旧 Host Tool、Web runtime、权限/相机等所有既有 Android component 调用点并未在本阶段强行全部重写为该 gateway。后续迁移这些调用点时必须复用本协议，而不是把 Context/Activity 拉回 Core。
- `InProcessPageProvider` 仍不能跨进程；依赖真实 Java View 的旧插件需要后续迁到声明式 document/state contract 或明确 Host-only component adapter。
- 本阶段未证明 Plugin Center renderer、ActivityResult、window lease 或 Host kill/reconnect 已在真机工作。
- Resident ON/OFF 总状态机、正常退出资源统一释放、Wake/CPU、网络冻结、LEV/freezer 与长锁屏持续工作仍未完成。
- `continuous_work` 仍不能因为 UI Proxy 源码完成而改为 true。
- 未修改 canonical Plugin Center / Bridge / Ubuntu / RDC / TriggerCMD / SentinelX 插件版本或 ABI；未修改 Current 注册表与 build25/code98 版本。

## 源码级完成口径

1. Core 是 screen/tile/theme/presentation/UI state/child contribution 的权威业务 owner；Host 只是镜像。
2. Host UI_PROXY 能恢复 Plugin Center UI-only renderer，但不初始化 PluginManager、业务 Child Runtime、Capability Registry、Bridge、Dispatcher 或 Ubuntu runtime。
3. Host 用户 UI action 能回到 Core owner 校验与 capability/provider/child command 路径。
4. Host 被重建或瞬时断连时，UI client 可以重新抓完整 snapshot，不启动第二套业务 runtime。
5. ActivityResult / window / start-activity 存在显式 Core↔Host component protocol，且 wire 上只有中性 JSON/ID。
6. Java View、Compose object、Context、ActivityResultLauncher、Window token、Binder/Parcelable 不进入 UI wire。
7. Step 6-8 的单一 Interaction Cycle / Policy / Dispatcher / Bridge / Plugin / Ubuntu owner，以及 build25 两个真机修复继续保留。

以上仍只是源码级边界。最终必须通过统一构建后的插件页面显示与操作、Host kill/restart、ActivityResult、Ubuntu/插件真实调用、长锁屏 Bridge 调用和 Resident OFF 资源释放一起验收。
