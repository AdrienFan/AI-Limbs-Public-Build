# Step 7 · Bridge 入口迁入 Resident Core

状态：源码边界已建立；未编译、未部署、未进行 Host kill / 锁屏真机验收。Provider 合约没有修改。

## 本阶段目标

Resident Core 已在 Step 6 持有唯一 Interaction Cycle / Policy / Dispatcher。本阶段只把 Bridge 业务入口接到这颗 Core brainstem 上，使外部 AI 调用不再以 Android Host Activity / UI 进程存活为前提。

目标调用链为：

`RDC / TRIGGERcmd / SentinelX / future provider transport -> BridgeRemoteIngress -> Core-owned Ingress -> Interaction Cycle -> Policy -> Dispatcher`

Bridge Provider 仍然只负责 transport、连接状态与协议适配；它不拥有 AI Limbs Interaction Cycle、receipt、WORK gate 或 Policy Engine。

## Provider ABI 保持不变

本阶段不修改 `BridgeRemoteIngress`、`BridgeRemoteIngressFactory`、`BridgeProviderFactory`、`BridgeProviderContribution` 或 `ai_limbs.bridge.provider@4` 合约。

当前 canonical Bridge `plugin.system.bridge` 仍通过现有 `HostBridgeRemoteIngress` 调用 `core.bridge.remote.invoke`。这里的 `host` 是 `InProcessPluginHost` 运行时契约，不再等价于 Android Host 进程：当 Bridge 被 Resident Core 的 BUSINESS Kernel 挂载时，这个 delegated capability 就在 Core 内部进入唯一 Dispatcher。

因此 RDC、TRIGGERcmd、SentinelX 以及以后任何遵守同一 Bridge Provider point 的实现，都不需要针对 Resident 单独增加 transport 白名单或修改 Provider 协议。

## Core 只恢复 Bridge 所需业务面

Step 7 不调用完整 `PluginPlatformKernel.start()`，也不恢复所有普通插件。Core 在 owner-only BUSINESS Kernel 已取得唯一 `plugin_kernel` lease 后执行定向恢复：

1. 启动 Host-owned `ChildExtensionRuntime`，只恢复 child 安装记录；没有 parent point 的 child 保持 BLOCKED，不会被顺手启动。
2. `PluginManager.restoreEnabledPlugin("plugin.system.bridge")` 只挂载已安装且 enabled 的 canonical Bridge parent。
3. Bridge parent 发布现有 `ai_limbs.bridge.provider@4` point。
4. 该 point 下已启用的 RDC / TRIGGERcmd / SentinelX / future provider child 被原有 child runtime 激活。
5. Core 等待该 point 下所有 enabled child 达到 ACTIVE；显式 FAILED 或超时会让 takeover 失败，不会回退 Host。
6. Bridge 未安装或被用户禁用属于显式配置，此时 Core 仍可成为业务 owner，但 status 会明确显示 `resident_bridge_plugin_mounted=false`。

这保证 Step 7 不偷跑 Step 8 的普通插件、Ubuntu 与设备能力全量恢复。

## Bridge 与 Core Dispatcher 的顺序

Core takeover 现在按以下顺序发布所有权：

`Host 退出 -> plugin_kernel lease -> 恢复门禁状态 -> owner-only BUSINESS Kernel -> permission backend claim -> Core Dispatcher start -> Bridge targeted restore -> markOwned`

先启动 Core Dispatcher，再恢复 Bridge。这样某个 Provider 在恢复后立刻收到外部请求时，`BridgeRemoteIngress` 已经有真实的 Core-owned Dispatcher 目标。只有 Bridge 定向恢复进入 prepared 状态后，takeover fence 才能从 `armed` 发布为 `owned`。

## Host UI 不再是 Bridge 必经节点

BUSINESS runtime 对 Bridge 运行时使用以下约束：

- `registerHomeTile` / `registerScreen` 在 Core 中只被抑制，不创建 Android UI surface；
- child `publishUiContribution` 在 Core 中变为 presentation no-op；
- child `createExtensionContext` 在 BUSINESS 下被禁止，防止 View / resource UI Context 偷渡进 Core；
- `host.notification@1` 在 BUSINESS 下提供 inert contract，Bridge 可以保持原业务 ABI，但不会因为发布通知而启动 Android Host `AIForegroundService`。

这些只是 presentation 隔离；Bridge manager、Provider contribution、网络 transport 和 remote ingress 仍留在 Core 业务面。真正 Core -> Host 的 UI 状态/事件同步仍属于后续 UI Proxy 阶段。

## transport session 不是 Interaction Cycle

现有 Provider 的 `beginSession()` 仍可以旋转 Bridge `scope_id`；`scope_id` 只表示 transport session。`core.bridge.remote.invoke` 会验证它存在，但不会用它创建、重置或作为 Interaction Cycle key。

Core 内部 External Bridge execution scope 仍由稳定的 `bridge:<provider>:<transport>` policy identity 表示，而 Interaction Cycle generation / bootstrap / receipts 始终来自 Step 6 的唯一 Core authority。Transport 断线重连、Provider beginSession 或换一个 socket/session 都不得重置门禁。

## fail-closed

- enabled Bridge parent 挂载失败：Core takeover 失败；不恢复 Host business runtime。
- enabled Bridge child 激活失败或超时：Core takeover 失败；不静默跳过再宣称 Bridge ready。
- Dispatcher 未启动：不会进入 Bridge restore，也不会发布 `owned`。
- Core 已拥有业务后，Host 重启仍只进入 UI_PROXY；Host 不会重建第二个 Bridge manager。

## 本阶段没有完成

- 普通插件、Ubuntu 与其他设备能力的 BUSINESS 恢复仍属于 Step 8。
- Bridge / child 的页面、通知和 ActivityResult 等真实跨进程 presentation 仍属于后续 UI Proxy。
- Provider 中需要显式打开授权页面的用户动作仍需要 Host/component proxy；这不影响后台 transport 收包与 remote invoke 的 Core 所有权。
- Resident ON / OFF 总状态机、CPU wake、网络冻结 / LEV 和长时间锁屏验收仍未完成。
- 本阶段未修改 canonical Bridge / RDC / TriggerCMD / SentinelX 源码或 Provider 协议，未修改版本号、Plugin Center 或 Current 注册表。

## 源码级完成口径

1. Core owner-only Kernel 能定向恢复 Bridge parent 与同 point 的 enabled children，而不会恢复其他普通插件。
2. Provider 的 `BridgeRemoteIngress` 最终在 Core 进程进入唯一 Interaction Cycle / Policy / Dispatcher。
3. Host Activity / UI 不参与上述数据面；BUSINESS runtime 不因 Bridge UI/notification 注册而拉起 Host。
4. transport `scope_id` 继续只是 transport session，不能影响 Interaction Cycle generation 或 receipts。
5. 所有未来 `ai_limbs.bridge.provider@4` Provider 复用同一动态 point，不增加 transport 特判。
6. Bridge restore 失败时 fail closed；takeover fence 不提前发布 `owned`。
7. build25 两个真机修复继续保留。

以上仍只是源码级完成，最终必须靠统一构建后的真实 RDC / TRIGGERcmd / SentinelX / future Provider 调用、Host 被杀与长时间锁屏测试验收。
