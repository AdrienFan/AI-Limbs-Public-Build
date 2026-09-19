---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/resident-runtime-build26
status: runtime-migration-in-progress
---

# Resident Core 分阶段迁移

目标是 Resident 开启期间，锁屏后 Bridge、Dispatcher、插件、Ubuntu 和设备能力继续可调用，关闭时释放工作资源。目标覆盖 Android 品牌，不以厂商名单为实现方案。

build24 的独立 Guardian 仍使用 App UID。PPID=1、oom_score_adj=-1000、进程存活和 WakeLock.isHeld 都不能证明 CPU 实际保持唤醒，也不能证明该 UID 不受冻结或网络策略限制。

build25 起点提供独立 app_process Context 与同 UID IPC；当前 build26 迁移源码已继续加入真实 Core 生命周期、权限后端 handoff 和 Plugin Kernel 唯一 owner 交接。Core 私有 IPC 只接受受限生命周期操作（status/stop/prepare_handoff/activate_business/cancel_business_activation/quiesce_business），不提供任意命令、任意能力执行或新的门禁旁路。

## 阶段

1. [独立启动和 IPC](01-bootstrap.md)：实现并送云构建；安装后验证 Context、资源、身份、重复启动和退出
2. 分离插件运行时和界面：现有 InProcessPageProvider 返回 Android View，不能直接经 IPC 传递，需定义状态与事件契约
3. 迁移唯一业务核心：Plugin Kernel、Bridge、Dispatcher 和插件服务；Interaction Cycle、授权及回执仍由唯一入口统一处理
4. 权限与持续运行：独立检验进程冻结、实际 suspend、网络、Android 组件权限和进程归属；需要特权的设备按已授权后端能力实施，不能假设同 UID 天然豁免
5. 真机验收：长时间锁屏持续外部调用，关闭 Resident 后核对核心退出和 CPU/网络资源释放

2026-09-15：继续编写后续迁移，不再把 build25 安装验证作为源码工作的前置门槛。本轮暂不编译。候选分支已包含 ac9373b 的 Android 16 Socket 初始化顺序与 Guardian 唯一实例锁修复，后续改动必须保留。

当前源码进度见 [运行时退出与交接边界](02-runtime-retirement.md)、[权限后端交接协议](03-backend-handoff.md)、[业务运行时 / 界面运行时拆分](04-runtime-role-split.md)、[Resident Core 独立业务进程骨架](05-core-process-skeleton.md)、[Plugin Kernel 唯一所有权交接](06-plugin-kernel-takeover.md)、[Host attach-only / UI Shell](07-host-ui-shell.md)、[Core-owned Interaction Cycle / Policy / Dispatcher](08-core-policy-dispatcher.md)、[Core-owned Bridge ingress](09-core-bridge-ingress.md)、[Core-owned plugin services / Ubuntu](10-core-plugin-services.md) 、[Core ↔ Host UI Proxy / Component Proxy](11-ui-proxy.md) 、[权限后端交接 + Resident ON/OFF 总状态机](12-resident-on-off-state-machine.md) 和 [Core CPU / 网络资源归属与源码总审计](13-core-continuous-resources-audit.md)。Core 已具备独立 Looper、owner-only BUSINESS Kernel、唯一门禁 / Policy / Dispatcher、Bridge 数据面、普通 Parent/Child plugin、Capability Registry、enabled Ubuntu 控制面，以及源码级 UI descriptor/state ↔ Host UI_PROXY 镜像与 command/component proxy。Host 重启只进入 UI_PROXY，不恢复第二套插件业务。Resident ON/OFF、permission backend ownership，以及 Core session 的 CPU Wake token / network callback 资源归属已在源码层形成闭环；历史 Android component 调用点已完成迁移，CPU/网络真实持续效果与真机验收仍未完成；不能把“UI Proxy 协议已建立”误记为锁屏持续工作已经通过。

## 迁移不变量（Step 1 冻结边界）

以下约束从 `7f335fd3a345a15fbcb9c17f294e5a6f238fdcf6` 起作为后续 Resident 迁移的硬边界。后续阶段可以补实现，但不得绕过或弱化这些约束；若实现与约束冲突，应先停下并显式修正设计，不能靠隐式 fallback 掩盖失败。

1. **唯一业务运行时所有者**：任意时刻只能有一个进程拥有 Plugin Kernel、Dispatcher、Bridge 业务入口、插件服务和其权威状态。Host 与 Core 可以同时存活，但不能同时恢复两套完整业务运行时。迁移窗口允许两个进程共存，仅允许一个业务 owner；新 owner 必须在旧 owner 完成收尾并真正释放所有权后接管。
2. **Host 最终职责边界**：Resident Core 持有业务运行时后，Host 只承担 Activity / UI、Android framework 组件以及明确的 UI / component proxy。Host 冷启动、Activity 重建或进程重建都不得因为 Core 已存在而再次恢复 Plugin Kernel 或插件业务副作用。
3. **Core 最终职责边界**：Resident ON 的目标是由 Core 持有持续业务生命线，包括后续迁入的 Plugin Kernel、Dispatcher、Bridge、插件服务、Interaction Cycle / Policy 权威状态和 Ubuntu / 设备能力控制面。Core 不直接承载 Android View、Compose 页面、ActivityResult、window token 等 Host UI 对象；这些必须走显式代理契约。
4. **禁止静默 fallback**：任何 handoff、owner 获取、后端接管、Host attach 或 Core 启动失败都必须产生明确失败状态和可观测诊断。不得在接管失败后静默恢复 Host 业务运行时，不得暗中启动第二个 Core，也不得用“看起来还能工作”替代正确所有权。
5. **所有权交接以真实退出和 lease 为准**：`plugin_kernel` 文件锁继续持有到旧进程退出；普通 `shutdown` 成功本身不等于旧 VM 已释放全部注册表、引用和访问入口。新 owner 只有在旧 owner 的在途调用收尾、旧进程退出并实际取得运行时 lease 后，才算完成接管。
6. **用户停止优先于交接**：Resident OFF、权限撤销和用户主动退出必须始终能够终止接管链并释放资源。只有经过显式验证、绑定当前 Core session 的 handoff retention permit 才能暂时保留权限后端；普通 shutdown / 卸载不得保留。
7. **build25 两个真机修复为不可回退基线**：`ResidentCoreWire.request()` 必须先 `socket.connect(...)` 再设置 `socket.soTimeout`；`AiLimbsResidentMain` 必须在主循环前获取 `ResidentRuntimeLease.acquire(stateDir, "guardian")`，并在 `finally` 中关闭 lease。后续所有重构必须保持这两个行为。
8. **存活不等于持续工作**：PID、PPID=1、`oom_score_adj=-1000`、WakeLock token / `isHeld` 或单纯进程存活都不能作为 CPU 实际持续执行、网络可用或跨品牌冻结豁免的验收证据。最终成功只能由正常 Bridge 链在长时间锁屏中的真实调用和 Resident OFF 后资源释放共同证明。
9. **门禁与授权只能有一份权威状态**：Interaction Cycle、WORK / NON_WORK、receipts、Policy 状态迁移到 Core 后必须只有一个权威 owner。不得让 Host 和 Core 各自维护 generation、receipt 或授权缓存并尝试事后合并。
10. **阶段提交纪律**：后续每一步只解决本阶段定义的边界，完成源码核查后单独提交；在阿伟明确要求统一构建前，不因为阶段完成自动编译、安装、晋升 Current 或修改 Plugin Center。

本 Step 1 只冻结上述迁移边界，不修改业务代码、版本号、已安装状态或 Current 注册表。


## Step 5 当前边界

重启 Host 在 Core 已成为业务 owner、handoff pending 或 takeover fence 阻止 fallback 时，只进入 UI_PROXY；不会初始化 Plugin Kernel，也不会启动 AIForegroundService、FloatingChatService 或 MCP/plugin loading。UI_PROXY 当前只提供安全的 UI registry 空壳；真正的 Core→Host UI 状态 / 事件同步与 Android component proxy 仍属于后续步骤。

## Step 6 当前边界

Interaction Cycle、Access Gate receipts、WORK / NON_WORK、Policy Engine 与 Dispatcher 的源码 authority 已交给 Resident Core：Host handoff 会冻结并一次性交接 generation / receipts / bootstrap 状态，Core 恢复同一周期后才启动独立 Dispatcher 数据面；takeover 后 Host ingress 只能经明确 Core IPC 代理，失败时 fail closed，不允许静默重建 Host-local Policy / Dispatcher。该结论仍为源码级，未编译、未部署、未进行 Host 重启或锁屏真机验收。

## Step 7 当前边界

Bridge 数据面现在按定向 BUSINESS restore 接入 Core：只恢复 `plugin.system.bridge` 与它现有 `ai_limbs.bridge.provider@4` point 下的 enabled child Provider；RDC、TRIGGERcmd、SentinelX 以及未来同合约 Provider 都继续只承担 transport，并沿原 `BridgeRemoteIngress -> core.bridge.remote.invoke` 进入 Core-owned Interaction Cycle / Policy / Dispatcher。Provider ABI 没有改变，transport `scope_id` 仍不是 Interaction Cycle。BUSINESS runtime 会抑制 Bridge/child 的 Android UI 与 Host notification presentation，避免为了 Bridge 后台调用重新拉起 Host。Step 8 随后把普通插件 / Ubuntu 的业务生命周期接入同一个 Core owner；真正 UI proxy、Resident ON/OFF 与 CPU / 网络持续运行仍属后续步骤。


## Step 8 历史边界（已由 Step 9 精确扩展 presentation 部分）

普通 enabled HOT Parent Plugin、其 active point 下的 enabled Child Extension、统一 Capability Registry，以及 canonical Ubuntu 的 runtime / PTY / command/process/filesystem 控制面进入 Resident Core 的 BUSINESS restore。Core 只在这些业务状态准备完成后发布 `owned`；enabled Ubuntu 必须 ACTIVE 且关键 capability 存在。Step 8 当时为了隔离进程边界而暂时抑制较广的 screen/home tile/UI state/child UI presentation；Step 9 已把其中纯数据 descriptor / JSON state provider 精确恢复为 Core-owned state 再镜像到 Host，但 `InProcessPageProvider -> View(Context)` 仍禁止进入 Core。Ubuntu 所需 archive application Context 被允许，但不引入 Activity/window token。Parent/Child stop、mount cleanup、scope join 或资源撤销失败都不能被视为 clean retirement，旧 owner 保持 pinned。

## Step 9 当前边界

Core 与 Host 现在有独立 `ResidentUiProxyWire`：Host 通过 revision snapshot 镜像 Core-owned screen/tile/theme/presentation/UI-state/child contribution/navigation 状态，并把 capability/provider/child/Plugin Center 管理动作作为 command 返回 Core；Host 只挂 UI-only Plugin Center renderer / Parent-Child presentation，不恢复 PluginManager、业务 Child Runtime、Capability Registry、Bridge、Dispatcher 或 Ubuntu。Host attachment 绑定 Core session + `host_instance_id` + 单调 `host_generation`，新 Host 会 retire 旧实例并回收其 component claims；UI server 可并发处理业务 command 与 component poll/result，避免同步 component rendezvous 自锁。Android Component Proxy 的 ActivityResult 有 deadline 清理，旧 Window lease 不得串到新 Activity。Ubuntu 完整终端与高级设置在 Host 只持 presentation controller，业务 mutation 通过 Core 私有 child presentation-command endpoint 执行。历史 Host Primitive / Host Tool 的所有 Android component 调用点尚未在本阶段全部迁到新 gateway。Step 10 已接通 Resident ON/OFF、permission backend return 与统一资源释放；LEV/freezer/CPU/网络持续运行和真机验收仍属后续阶段。


## Step 10 当前边界

Resident 开关现在在源码层代表业务核心切换：ON 按 Core start → backend prepare → Host ingress freeze/drain → Host runtime retirement → Core claim → Guardian 冷拉 Host → UI_PROXY generation attach 完成；只有 Core business owner 与 Host attach 都成立才报告 `on`，中间明确为 `host_attach_pending`。OFF 按 stop admission → Dispatcher drain → Core shutdown → permission backend return-to-host → ownership confirmation → fence/lease/Guardian cleanup → 冷启动普通 Host。Core 异常死亡时 permission backend 回到 UI_PROXY recovery/control plane而不恢复 Host business；backend 本身丢失时允许 degraded OFF，避免用户被锁死。该结论仍为源码级，未编译、未安装、未进行真机 ON/OFF 或锁屏验收。


## Step 11 当前边界

持续 CPU / 网络资源现在绑定 Resident Core business session：Core 在 `activate_business` 时获取 `PARTIAL_WAKE_LOCK` token 与 default network callback，takeover cancel / normal exit / OFF 统一释放；真实 plugin / Bridge / Host Network Primitive 网络 I/O 在 Resident BUSINESS 下由 Core 执行。Host 不再 acquire Resident WakeLock，Host 侧仅保留旧 token / state 的兼容释放与诊断；Guardian 只根据 `host_shell.state` 判断是否需要冷拉 framework shell。总状态把 Core process liveness、Wake token、Android network availability / validation 与 `continuous_work` 分开报告；`continuous_work` 仍固定为 false / unverified，PID、`oom_score_adj=-1000`、WakeLock.isHeld、NetworkCallback 均不能作为 LEV / freezer 成功证据。Core status 新增 ownership consistency，business owner 与 CPU/network session owner、Dispatcher、Policy 任一不一致即 fail closed；资源释放失败保留 residual owner / degraded 诊断。owner、stop、death-recipient、UI component generation、no-fallback 与 Step 7 `scope_id` transport-only 规则已完成源码总审计。该结论仍为源码级，未编译、未安装、未做长锁屏真实 Bridge→Ubuntu / plugin 调用。

## build31 启动故障修复

[Core 调用身份与跨域存活判断修复](14-build31-context-identity.md)：从 build30 工作树修正应用 Context 的系统调用归属，统一五处 SELinux 存活判断并补齐请求异常堆栈。候选版本为 build31/code104；按用户指令只提交云构建，不执行本地编译，尚未真机验收。


## build32 失败恢复与进程依赖预检

[Core 进程依赖预检与 Resident 失败恢复](15-build32-process-init-and-recovery.md)：依据 build31 真机 `androidPermissionPreferences` 未初始化与 `failed` takeover fence 导致插件系统不可见的现场，补齐独立 Core 的进程 Context/权限偏好预检、Host-only singleton 清理、失败 stage 诊断和 Base 自有的 `UI_PROXY_BLOCKED` 显式 OFF 恢复入口。候选版本为 build32/code105；不允许静默 fallback，也不清插件数据。


## build58 隔夜恢复

[隔夜 owner 丢失后的恢复与取证](19-build58-overnight-owner-loss.md)：补齐 Host 重开和 watchdog 的 Core 恢复触发，限制 Guardian 系统调用等待，保留恢复前证据。现场已查到系统 silent reset 和无线 ADB 关闭；00:10 首次心跳中断原因仍待证实。
