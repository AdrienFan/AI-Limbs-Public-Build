# Step 6 · Interaction Cycle / Policy / Dispatcher 迁入 Resident Core

状态：源码边界已建立；未编译、未部署、未触发真机 Host 退出，也未接入 Resident ON/OFF 总状态机。

## 本阶段目标

把 AI Limbs 的“脑干入口”从 Android Host 移到 Resident Core。Core 成为 Interaction Cycle、门禁回执、WORK / NON_WORK、Policy Engine 与 Dispatcher 的唯一权威 owner；Host 在 Core 接管后只保留入口代理，所有正常 AI Limbs 调用必须通过明确 IPC 进入 Core。

本阶段最重要的不变量是：**generation 与 receipt 必须属于同一份权威状态。** 不允许 Host 保存 receipt、Core 保存 generation，也不允许两个进程各自维护一份状态后再合并。

## 权威状态交接

旧 Host 在退出前执行一次性 policy handoff：

1. 确认 Host 仍是当前 `LEGACY_HOST` Plugin Kernel owner。
2. `AiLimbsInteractionCycleRuntime.freezeAndExportForResidentHandoff()` 先冻结新的外部 ingress，同时冻结 Access Gate 内可能改变 receipt / WORK gate / discovery ledger 的直接 mutation；handoff 要求当前 `active_invocations=0`，并拒绝与 pending manual reset 并发。
3. 一次性导出当前 Interaction Cycle 的 `generation`、`cycle_started_at_ms`、expiry 状态、Access Bootstrap delivery generation，以及 Access Gate 内的 custom access prompt receipt、Work Manual receipt、WORK / NON_WORK gate 和 subsystem discovery ledger。
4. `ResidentPolicyStateHandoff` 把这份状态写入私有、大小受限的 `policy_handoff.json`，并绑定 `core_session + core_pid + host_pid`。
5. handoff 冻结期间的新入口返回 `RESIDENT_POLICY_HANDOFF_PENDING`，不会继续修改旧 Host 的门禁状态。
6. Host 完成 Plugin Kernel retirement 并真正退出；Core 观察旧 Host PID 消失并取得同一 `plugin_kernel` lease 后，消费这份一次性 handoff。
7. Core 用 `restoreFromResidentHandoff()` 恢复原 generation / receipts / WORK 状态，再创建 Policy / Dispatcher 数据面。

如果 staging、arm 或 Host retirement 在旧 Host 仍存活时失败，handoff 文件会被清理、takeover 会被取消，并解除 policy freeze；这属于旧 owner 继续保有权威状态，不是 fallback。

## Core Dispatcher 数据面

生命周期控制仍使用原有 `ResidentCoreWire`；工具调用新增独立 `ResidentCoreDispatchWire`，避免长时间能力执行阻塞 `status / stop`。

Dispatcher 数据面使用独立 abstract LocalSocket：

`ai_limbs_core_dispatch_<uid>`

协议要求同 UID peer、当前 Core session、request id、Core PID / UID 一致；帧大小有上限，调用超时独立于生命周期 socket。

Core 中的 `ResidentCoreDispatcherRuntime` 持有唯一 `AiLimbsInteractionCycleRuntimeState`，并通过 `AiLimbsIngressGateway.authoritativeCore(...)` 进入原有执行链：

`Ingress -> Interaction Cycle -> Access Bootstrap -> Policy Engine -> Dispatcher -> Core capability / Host tool`

因此迁移后 Policy Engine 不再自行创建私有 `AiLimbsAccessGate`；所有 execution session 都引用同一 Interaction Cycle authority 的 receipt ledger。

## Host 调用与禁止 fallback

`AiLimbsIngressGateway` 在 Host 侧检测 Resident takeover ownership：

- 未发生 takeover 时，继续使用 legacy Host-local 路径，保持当前兼容行为。
- takeover fence 为 `owned` 时，Host 使用 `ResidentCoreDispatcherClient` 把调用明确转发到 Core。
- takeover 为 `armed`、`failed`、fence 非法、Core Dispatcher 不可达或 session 不一致时，调用明确失败，并报告 `fallback_allowed=false`。
- Host 不得因为 Core 暂时不可用而重新创建本地 Interaction Cycle / receipt / Policy / Dispatcher。

`AiLimbsInteractionCycleRuntime.state()` 同时增加了 ownership fence：只要 takeover fence 已存在，非绑定的真实 `ail_resident_core` 进程就不能建立本地 policy authority。PID 本身不足以证明身份，还会核对当前进程 cmdline，避免 PID 复用造成错误授权。

## 所有权发布顺序

Core 只有在以下步骤都成功后才把 takeover fence 从 `armed` 标记为 `owned`：

`Host 退出 -> Core 取得 plugin_kernel lease -> 恢复 Interaction Cycle / receipts -> 启动 BUSINESS Kernel -> claim permission backend -> 启动 Core Dispatcher -> markOwned`

因此不会出现“fence 已显示 owned，但 Dispatcher / Policy plane 尚未准备好”的假成功状态。

Core status / Dispatcher status / Policy describe 均暴露 owner、owner PID 与 Interaction Cycle generation，便于后续真机验证 Dispatcher owner 是否确实为 Core。

## Host 重启语义

Step 5 已保证 takeover 存在时 Host 重启只进入 UI_PROXY，不重新启动业务 Kernel。Step 6 进一步保证：Host 重启也不会重新生成门禁周期、receipt 或 generation。

只要 Core 仍是业务 owner：

- generation 继续由 Core 中原 handoff 状态演进；
- custom access prompt / Work Manual receipts 继续由同一 Core ledger 持有；
- WORK / NON_WORK 选择不会因 Host Activity / 进程重建而重置；
- Host 入口只能调用 Core Dispatcher，不能拥有第二份 Policy Engine 权威状态。

## 本阶段没有完成

- Bridge provider 本身尚未迁入 Core；本阶段建立的是 Core-owned brainstem 与 Host ingress proxy。
- 普通插件、子插件、Ubuntu 与设备能力服务尚未恢复到 Core。
- Plugin Center / 插件 UI 的跨进程状态与事件代理仍属于后续 UI proxy 阶段。
- Resident ON / OFF 尚未接入 one-way handoff / shutdown 总状态机。
- CPU、网络、Freecess / LEV、长时间锁屏持续调用尚未进行真机验收。
- 本阶段未编译、未安装、未修改版本号、Plugin Center 或 Current 注册表。

## 源码级完成口径

本阶段源码完成必须同时满足：

1. Interaction Cycle 与 Access Gate 只有一个 runtime authority，Policy Engine 不再私建 receipt ledger。
2. handoff 连同 generation、cycle start、bootstrap state、receipts 与 WORK gate 一次性交给 Core。
3. Host handoff 期间冻结新 ingress；失败能显式取消冻结，不留下半交接状态。
4. Core Dispatcher 使用独立、session-bound、同 UID IPC；Host-owned ingress 在 takeover 后只做代理。
5. Core 不可达、takeover pending / failed 或 ownership 异常时 fail closed，绝不静默回 Host-local Policy / Dispatcher。
6. `business_attached=true` 时必须同时验证 Core Dispatcher / Policy owner 为 `resident_core` 且 owner PID 等于 Core PID。
7. build25 的 Android 16 LocalSocket connect-before-timeout 与 Guardian singleton lease 两个真机修复继续保留。

以上结论仅为源码级，必须在后续统一构建和真机 handoff / Host 重启 / 锁屏测试中验证。
