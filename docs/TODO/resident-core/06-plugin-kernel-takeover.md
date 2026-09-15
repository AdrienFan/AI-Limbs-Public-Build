# Plugin Kernel 唯一所有权交接

状态：Step 4 源码边界已建立；未编译、未部署、未触发真机 Host 退出，也未接入 Resident ON/OFF。

## 本阶段目标

第一次把 Plugin Kernel 的唯一业务所有权从旧 Android Host 移交给 Resident Core。这里的“接管”只指 **Plugin Kernel owner**：Core 本阶段以 `BUSINESS + owner-only` 启动 Kernel，不恢复普通插件、子插件、Bridge、Dispatcher 或 Ubuntu，因此 `plugins_migrated=false`、`continuous_work=false` 继续保持。后续阶段再逐层迁业务。

## 交接顺序

1. Host 先确认自己仍是 `LEGACY_HOST`、Kernel 正在运行并持有 `plugin_kernel` 进程锁。
2. Host 通过既有 `prepareHandoff` 让 Core 准备权限后端；permit 绑定后端实例、Core PID 与 Core session。
3. Host 调用 Core 的内部 `activate_business`。Core 从同 UID Unix socket 的 `peer.pid` 取得真实 Host PID，不接受调用参数伪造 owner PID。
4. Core 写入持久 takeover fence，状态进入 `waiting_for_host_exit`，随后立即向 Host 回 ACK；异步 takeover worker 只等待，不提前获取 `plugin_kernel`。
5. Host 收到 ACK 后调用 `PluginPlatformKernel.shutdownForResidentHandoff()`。退出失败则在 Host 仍持有进程锁时取消 takeover；这是原 owner 继续保有所有权，不是 fallback。
6. Host 只有在 Kernel 明确进入 `stopped` 且仍持有 owner lease 后，才进入终态进程退出。`plugin_kernel` 锁由进程死亡自然释放。
7. Core 必须先观察旧 Host PID 已退出，再 `tryAcquire(plugin_kernel)`；没有实际取得锁就不能初始化 BUSINESS Kernel。
8. Core 在主 Looper 上用预先取得的 lease 初始化 `PluginPlatformKernel(BUSINESS)`，调用 `startOwnerOnly()`。该入口只建立 Kernel owner，不 restore 插件/子插件业务。
9. Core 核对 Kernel role=business、PID=Core、owner lease held、started=true、business_runtime_restored=false 后，才调用 `ResidentBackendBinding.claimRuntimeOwnership()`。
10. 后端 claim 成功后 takeover fence 变为 `owned`，Core 才报告 `business_attached=true / runtime_owner=resident_core`。

## 禁止静默 fallback

`business_takeover.json` 是跨 Host 重启的持久 no-fallback fence。只要 fence 存在，`PluginPlatformKernel.initialize(LEGACY_HOST)` 就拒绝重新创建 Host 业务 Kernel。

- Host 在自己仍是旧 owner、尚未完成退休前可以显式取消 `armed` fence。
- Core 接管成功后 fence 保持 `owned`。
- Core 在 Host 已退出后的接管失败会留下 `failed` fence；Host 不得因为 Core 失败就静默恢复业务。
- 只有显式 Core stop/后续 Resident OFF 编排才能清理当前 Core session 的 fence。

因此失败结果可能是“业务暂不可用但所有权状态明确”，而不能是“看起来还能用、实际悄悄出现第二个 owner”。

## 状态与诊断

Core 业务阶段：

`detached -> waiting_for_host_exit -> acquiring_owner -> starting_kernel -> claiming_backend -> running`

取消/停止/失败分别进入 `cancelled / stopped / failed`。Core status 同时报告 `business_phase`、`expected_host_pid`、`plugin_kernel_started`、Kernel lifecycle、backend 状态与 takeover fence。

当 `business_attached=true` 时，必须同时满足：

- `runtime_owner=resident_core`
- Core Kernel `runtime_role=business`
- `owner_lease_held=true`
- `started=true`
- `business_runtime_restored=false`（Step 4）
- 权限后端已完成 `claimRuntimeOwnership`

## 当前没有接线的部分

`ResidentPluginKernelHandoff.execute()` 目前是内部交接编排入口，**没有**挂到 Plugin Center、Host primitive 或 Resident ON/OFF；因此本阶段提交本身不会在当前真机上自动杀 Host 或改变用户开关行为。真正把开关接入这个 one-way handoff 属于后续 Resident 总状态机阶段。

Host 重启后的正确形态仍待 Step 5：有 takeover fence/Core owner 时 Host 应进入 UI_PROXY attach-only，而不是尝试 LEGACY_HOST。Step 4 先用硬拒绝保证“不产生第二套 Kernel”。

Bridge、Dispatcher、Interaction Cycle、插件服务、Ubuntu 与 UI proxy 尚未迁移，不能因为 Core 已持有 Plugin Kernel 就声称 AI Limbs 已完成业务迁移或锁屏持续工作。

## 本阶段完成口径

源码上已存在一条不重叠的所有权链：旧 Host 持锁 -> Host 正确退休 -> Host 进程退出 -> Core 实际取得同一 `plugin_kernel` 锁 -> Core owner-only BUSINESS Kernel 启动 -> 后端 claim -> Core 成为唯一 Kernel owner。任一步失败都保留明确状态，且不存在“接管失败后自动恢复 Host 业务”的代码路径。

本结论仅为源码级；未经过编译和真机交接验证。
