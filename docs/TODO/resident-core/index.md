---
fork: https://github.com/AdrienFan/AI-Limbs-Public-Build
branch: feat/resident-runtime-build26
status: runtime-migration-in-progress
---

# Resident Core 分阶段迁移

目标是 Resident 开启期间，锁屏后 Bridge、Dispatcher、插件、Ubuntu 和设备能力继续可调用，关闭时释放工作资源。目标覆盖 Android 品牌，不以厂商名单为实现方案。

build24 的独立 Guardian 仍使用 App UID。PPID=1、oom_score_adj=-1000、进程存活和 WakeLock.isHeld 都不能证明 CPU 实际保持唤醒，也不能证明该 UID 不受冻结或网络策略限制。

build25 起点提供独立 app_process Context 与同 UID IPC；当前 build26 迁移源码已继续加入真实 Core 生命周期、权限后端 handoff 和 Plugin Kernel 唯一 owner 交接。Core 私有 IPC 只接受受限生命周期操作（status/stop/prepare_handoff/activate_business/cancel_business_activation），不提供任意命令、任意能力执行或新的门禁旁路。

## 阶段

1. [独立启动和 IPC](01-bootstrap.md)：实现并送云构建；安装后验证 Context、资源、身份、重复启动和退出
2. 分离插件运行时和界面：现有 InProcessPageProvider 返回 Android View，不能直接经 IPC 传递，需定义状态与事件契约
3. 迁移唯一业务核心：Plugin Kernel、Bridge、Dispatcher 和插件服务；Interaction Cycle、授权及回执仍由唯一入口统一处理
4. 权限与持续运行：独立检验进程冻结、实际 suspend、网络、Android 组件权限和进程归属；需要特权的设备按已授权后端能力实施，不能假设同 UID 天然豁免
5. 真机验收：长时间锁屏持续外部调用，关闭 Resident 后核对核心退出和 CPU/网络资源释放

2026-09-15：继续编写后续迁移，不再把 build25 安装验证作为源码工作的前置门槛。本轮暂不编译。候选分支已包含 ac9373b 的 Android 16 Socket 初始化顺序与 Guardian 唯一实例锁修复，后续改动必须保留。

当前源码进度见 [运行时退出与交接边界](02-runtime-retirement.md)、[权限后端交接协议](03-backend-handoff.md)、[业务运行时 / 界面运行时拆分](04-runtime-role-split.md)、[Resident Core 独立业务进程骨架](05-core-process-skeleton.md)、[Plugin Kernel 唯一所有权交接](06-plugin-kernel-takeover.md)、[Host attach-only / UI Shell](07-host-ui-shell.md) 和 [Core-owned Interaction Cycle / Policy / Dispatcher](08-core-policy-dispatcher.md)。Core 已具备独立 Looper、owner-only BUSINESS Kernel 接管链与源码级 Core-owned Interaction Cycle / Policy / Dispatcher；Host 重启已能根据 Core / takeover fence 进入 UI_PROXY，并禁止第二套 Plugin Kernel、门禁状态与本地 Dispatcher authority。Bridge provider、普通插件 / Ubuntu、跨进程 UI 状态事件代理及 Resident ON/OFF 编排仍未接通。不能把“Core 已拥有 brainstem”误记为完整插件运行时、Bridge provider 或锁屏持续工作已完成。

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

Interaction Cycle、Access Gate receipts、WORK / NON_WORK、Policy Engine 与 Dispatcher 的源码 authority 已交给 Resident Core：Host handoff 会冻结并一次性交接 generation / receipts / bootstrap 状态，Core 恢复同一周期后才启动独立 Dispatcher 数据面并发布 `owned` fence；takeover 后 Host ingress 只能经明确 Core IPC 代理，失败时 fail closed，不允许静默重建 Host-local Policy / Dispatcher。该结论仍为源码级，未编译、未部署、未进行 Host 重启或锁屏真机验收；Bridge provider、普通插件 / Ubuntu、UI proxy 事件契约和 Resident ON/OFF 总状态机仍属后续步骤。
