# Step 10：权限后端交接与 Resident ON/OFF 总状态机

状态：源码级完成，尚未编译、安装或真机验收。

本阶段把此前独立完成的 Core、Plugin Kernel、Policy/Dispatcher、Bridge、普通插件/Ubuntu、UI Proxy 与 permission backend 串成一个真正的 Resident 业务切换状态机。Resident 开关不再表示“Guardian 进程是否存在”，而表示 AI Limbs 业务核心是否从 LEGACY_HOST 原子切换到 Resident Core，以及关闭时是否完整回到普通 Host。

## ON：从普通 Host 切到 Resident Core

正常路径：

1. 持久化 Resident desired state = ON，并启动/复用唯一 Guardian。
2. 启动独立 Resident Core skeleton。
3. Core 对当前 AI Limbs permission backend 执行 prepare，只建立绑定当前 Core session/lifetime 的 prepared ownership，不立即发布 business owner。
4. Host 先冻结新的 Interaction Cycle ingress；后续新 invocation 返回 `RESIDENT_POLICY_HANDOFF_PENDING`。
5. Host 等待已有 invocation drain 到 0，只有零在途调用才导出 generation / receipts / WORK / bootstrap 等唯一 Policy 状态。
6. stage policy handoff，arm Core business takeover。
7. Host Plugin Kernel 使用 prepared backend retention permit 收尾业务 runtime；旧 Host 保持 `plugin_kernel` lease 到进程真实退出。
8. 旧 Host 退出后，Core 取得唯一 `plugin_kernel` lease，恢复同一 Policy/Interaction Cycle，启动 BUSINESS Kernel、Dispatcher、Bridge、普通插件、Child 与 Ubuntu。
9. Core claim permission backend，backend `runtime_owner` 进入 `resident_core`。
10. Guardian 发现旧 Host/wake lease 消失后，通过 `ACTION_RESIDENT_KEEPALIVE` 冷拉 Android Host。新 Host Resolver 只能进入 UI_PROXY，不恢复第二套 business runtime。
11. UI_PROXY Host 使用当前 Core session + 随机 `host_instance_id` attach；Core 生成单调 `host_generation`。只有 `business_attached=true`、UI proxy server running、`host_attached=true` 且 generation > 0 时，总状态才是 `on`。Core 已 business-owned 但 Host 尚未 attach 时明确报告 `host_attach_pending`。

因此 ON 的完成条件不是 PID 存活，也不是 Guardian 已启动，而是：**Core 已成为唯一业务 owner + permission backend 已被 Core claim + Host 已作为 UI_PROXY attach。**

## ON 失败回滚

permission backend prepare 之后的任一步都属于同一个事务边界。Host drain、policy stage、takeover arm 或 Host runtime retirement 任一步失败时：

- 清除已 stage 的 policy handoff；
- 在尚可取消的阶段撤销 Core takeover；
- 解除 Host policy freeze；
- 停止 Core；
- 由 Core stop/release 把 prepared permission lifetime 明确交回仍存活的 Host。

不允许留下“Resident ON 失败，但 backend 仍停在 `handoff_prepared` / Core lifetime”这种半交接状态。失败必须记录 `last_error` 并由总状态报告 `failed`；UI_PROXY 已失去 Core 时不允许静默回到 Host business，要求显式 Resident OFF recovery。

## OFF：从 Resident Core 回到普通 Host

正常路径：

1. 先持久化 desired state = OFF；即使后续清理降级，用户的 OFF 意图优先。
2. 若 Core 是 business owner，先执行 `quiesce_business`：Dispatcher `accepting=false`，关闭 accept socket，停止接收新任务。
3. 等待已接收 worker drain。正常 drain 最多等待 5 秒；若超时则强制取消剩余工作并记录 degraded，但 OFF 继续推进。
4. Core shutdown：退休 Plugin/Child/Ubuntu/Bridge/Dispatcher/Kernel，并释放 `plugin_kernel` owner。
5. Core 对 permission backend 执行 release-to-host。正常 release 后 backend 先进入 `returning_to_host`，并不立即冒充 `android_host`。
6. permission-server 通过 Host ContentProvider 回交同一个 Binder。只有 Provider 真正接受后调用 `markHostAttached()`，backend 才进入 `android_host`。Host 侧 OFF 最多等待 12 秒并用私有 ownership protocol 验证 `runtime_owner=android_host` 且 `core_lifetime_alive=false`。
7. 清理 takeover fence / policy handoff，确认 Core bootstrap lease 和 `plugin_kernel` lease 已释放。
8. 停止 Guardian、释放 Resident Host wake responsibility。
9. UI_PROXY Host 安排一次冷启动并退出当前进程；新 Host 在没有 Core/fence/policy handoff 的事实下进入普通 LEGACY_HOST，并重新拥有正常业务 runtime。

## Core 异常死亡与 OFF recovery

permission-server 的 Core lifetime death recipient 不再直接终止 backend。Core 异常死亡时：

- owner 清空；
- backend 进入 `returning_to_host`；
- permission-server 调度 Host handoff；
- UI_PROXY Host 可以先恢复权限 recovery/control plane，但 Step 9 仍禁止它原地恢复业务 runtime；
- 用户执行 OFF 后即可继续清理 fence/lease/Guardian，并冷启动回普通 Host。

Plugins Resident candidate 配套提交：`692111c` — `refactor(resident): return permission backend to host`。

这条恢复路径消除了“Core 已死、permission-server 也退出、UI_PROXY 又不能恢复普通业务，因此 OFF 无法完成”的死锁。

## Permission backend 自身丢失

“OFF 永远能生效”不能依赖 permission-server 永远健康。如果 backend 本身已经死亡或 12 秒恢复窗口内无法重新 attach，但已经能证明：

- Core PID 已退出；
- Core bootstrap lease 已释放；
- `plugin_kernel` lease 已释放；
- takeover/policy 残留可以安全清除；

则 OFF 允许以 `backend_lost_or_unreachable` 的 degraded 结果完成，并冷启动普通 Host。此时不会伪装权限后端正常；普通 Host 只是恢复业务模式，特权 backend 明确处于不可用状态，可由权限服务后续重新启动。

正常路径仍要求实际确认 `android_host` ownership；只有 backend 本身失效时才走上述降级出口。

## 总状态

Host 控制面现在区分至少这些阶段：

- `starting_guardian`：Resident desired ON，但 Guardian 尚未就绪；
- `starting`：Guardian 已存在，Core/prepare 正在建立；
- `handoff`：prepare / Host retirement / Core takeover 过程中；
- `host_attach_pending`：Core 已经是唯一 business owner，但新 UI_PROXY Host 尚未完成当前 Core session 的 attach generation；
- `on`：Core business owner + UI_PROXY Host attach 已完成；
- `stopping`：Resident desired OFF，资源正在回收；
- `off`：Core/Guardian/fence/policy 均已退出，普通 Host 可恢复；
- `failed`：ON/运行期发生明确错误；
- `off_failed`：OFF 仍有无法证明释放的 Core/lease/fence 等残留，需要继续恢复。

`off_degraded=true` 与 `off_failed` 不同：前者表示 OFF 已完成但发生可观测降级（例如 backend 丢失或强制取消在途任务）；后者表示 OFF 的核心资源释放尚未得到证明。

## 本阶段不改变的边界

- Host UI_PROXY 仍不得恢复 PluginManager、业务 Child Runtime、Capability Registry、Bridge、Dispatcher 或 Ubuntu。
- Core 仍不持有 Activity / Window / ActivityResultLauncher / View / Compose object。
- Step 7 transport `scope_id` 仍只是 transport metadata，不是 Interaction Cycle key。
- build25 Android 16 LocalSocket 必须先 `connect()` 再设置 `soTimeout`。
- Guardian 仍必须持有唯一 `ResidentRuntimeLease.acquire(stateDir, "guardian")` 并在 finally 关闭。
- `continuous_work` 仍为 false / unverified；本阶段没有证明 Wake/CPU/网络/freezer/LEV 长锁屏持续工作。
- 版本仍为 `0.8.0.5-build25/code98`，不在本阶段编译、安装、云构建、晋升 Current 或修改 Plugin Center。

## 源码级完成口径

1. Resident ON 只有在 Core business ownership、permission backend claim 与 Host UI_PROXY attach 三者都成立时才报告 `on`。
2. Host 在 ON prepare 之后先拒绝新 invocation，并等 active invocation 归零后才导出 Policy state。
3. prepare 之后任一步失败都有完整 rollback，不留下 prepared backend / policy freeze / armed takeover 半状态。
4. Resident OFF 明确执行 stop-admission → drain → Core shutdown → backend return → Host ownership confirmation → Guardian/resource cleanup → ordinary Host restart。
5. drain 超时、Core graceful stop 失败或 Core 异常死亡都不能让 OFF 永久失效；状态必须区分 clean / recovered / degraded / failed。
6. permission backend 正常回交只有在 Host 真正接受 Binder 后才报告 `android_host`。
7. backend 自身丢失不能锁死 OFF，但必须以 degraded 明确暴露，不能假装权限正常。
8. 所有旧的唯一业务 owner、Policy/receipt、UI_PROXY 与 Android component boundary 继续成立。

以上只是源码级状态机闭环。下一阶段仍需处理/验证 CPU Wake、网络持续性、Samsung LEV/freezer 等电源与冻结行为，并在统一 build26 真机上验证：Resident ON → Host kill/restart → Bridge/Ubuntu 真调用 → 长锁屏 → Resident OFF → backend/lease/wake/resource 全部释放。
