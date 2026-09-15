# Step 8 · 插件服务与 Ubuntu 控制权迁入 Resident Core

状态：源码业务所有权链已建立；未编译、未部署、未进行 Ubuntu / 设备能力 / Host kill / 锁屏真机验收。

## 本阶段目标

Step 7 只把 Bridge 数据面定向恢复到 Core。本阶段把其余真正的业务“手脚”迁入同一个 BUSINESS owner：普通 Parent Plugin、对应 Child Extension、插件 Capability Registry、System Environment parent 与 Ubuntu child 的运行控制/PTY/能力端点。Android Host 不再恢复第二套插件业务运行时；页面渲染、Activity/window、ActivityResult 与 framework component 继续属于 Host / 后续 component proxy。

Core takeover 的发布顺序现在是：

`Host exit -> plugin_kernel lease -> policy handoff -> BUSINESS Kernel -> permission backend -> Core Dispatcher -> Bridge -> ordinary plugin services / children / Ubuntu capabilities -> markOwned`

只有最后一段完成后，takeover fence 才允许发布 `owned`。

## Parent / Child 业务生命周期归 Core

BUSINESS Kernel 继续复用 Step 7 已启动的 Child Runtime，并调用同一个 `PluginManager.restoreEnabledPlugins()` 恢复其余 enabled HOT parent。Bridge parent 已经 ACTIVE 时恢复是幂等的，不会创建第二个 Bridge runtime。Parent 发布 child point 后，Core 等待所有“enabled 且 parent point 已在线”的 child 达到 ACTIVE；明确 FAILED 或等待超时都不会被当成 clean readiness。

这意味着 parent services/providers（非 UI presentation）、child binding、child runtime controller、child discovery、child capability executor 与其状态都驻留在 Core 的唯一 Plugin Kernel 内。Host UI_PROXY 不挂载这些业务 runtime。

单个普通 parent 自身如果因为安装状态、依赖或 capability 冲突进入 FAILED/BLOCKED，会留在 Core-owned lifecycle 状态和诊断报告中，不会通过 Host fallback 掩盖。Ubuntu 是本阶段的显式关键能力：如果 canonical Ubuntu child 已安装且 enabled，则它必须真正 ACTIVE 且关键 `plugin.ubuntu.command` / `plugin.ubuntu.process` capability 已注册，Core 才允许把 takeover 发布为 owned。

## Ubuntu 控制面

canonical System Environment parent 仍使用现有 `plugin.system.environment_center` 与 `ai_limbs.system_environment.subsystem@1` 合约；canonical Ubuntu child 仍是 `ai_limbs.system_environment.ubuntu`。本阶段不修改这些插件的 ABI 或版本。

Ubuntu child 启动时需要 archive ClassLoader/resources/native-runtime Context，因此 Step 7 对 BUSINESS child `createExtensionContext()` 的“全部禁止”被收窄：Core 可以提供独立 application/archive runtime Context，但不会提供 Activity/window token。真正 presentation 仍受以下边界约束：

- parent `registerScreen` / `registerHomeTile` 在 BUSINESS 中继续抑制；
- `InProcessPageProvider` / `InProcessUiStateProvider` 这两种 Stable SDK presentation provider 在 BUSINESS 中不注册到业务 provider registry；
- child `publishUiContribution` 在 BUSINESS 中继续 no-op；
- Host notification presentation 继续使用 inert contract；
- Android component / ActivityResult / window token 不迁入 Core。

因此 Ubuntu 的 TerminalManager、PTY、rootfs/runtime controller、filesystem/process/command capabilities 可以归 Core，而其 View/Compose 页面仍不成为 Core-owned presentation。Ubuntu contribution 为兼容现有 parent contract 仍携带 display adapter 对象，但 BUSINESS Core 不调用其 `createView` / `createConfigurationView`；真正把 Core 状态和 display 事件投影回 Host 页面属于后续 UI proxy 阶段。

System Environment Center manifest 仍声明 `system.plugin_center.ui_accessories`，但它的源码本来就把该 service 当成可空的 UI 附件。BUSINESS dependency resolver 因而只对这一明确的 Host presentation service 放宽“必须在线”的硬依赖；Legacy Host 语义不变，其他业务 service dependency（例如 delegated gateway）不会被一并忽略。这样不需要修改 parent ABI/包版本，也不会为了一个 UI accessory 把 Plugin Center UI system plugin 拉进 Core。

## Capability Registry 唯一性与重复注册

插件 capability 继续只经过 Core 这一份 `PluginHostCapabilityRegistry -> AiLimbsCapabilityRegistry`：

1. canonical capability id 先由 owner-token map 使用 `putIfAbsent` 抢占；
2. canonical + invoke aliases 在全局 registry 的同步区一次性检查 Core / plugin 冲突后再写入；
3. 任一 alias / canonical 冲突都会撤回本次 local candidate，不允许部分注册；
4. close 只删除 token 与当前 owner 匹配的注册，旧 handle 不能误删后来 owner；
5. child capability 先注册 contribution，再注册 capability；后者失败会立刻撤回 contribution。

所以“重复注册但最后一个覆盖前一个”的行为仍然禁止。冲突必须显式失败并进入插件/child lifecycle 诊断。

## stop / 资源撤销失败必须保留所有权

本阶段继续强化 fail-closed retirement：

- parent `PluginMountScope.revokeAll()` 会撤销 caller lease，并逆序关闭 capability/service/provider/extension 等 owned handles；任何 close failure 都被保留；
- runtime handle stop 失败或超时会返回非 clean stop，`PluginManager` 不从 `activeMounts` 删除 owner；
- mount 本身失败时，失败清理现在也检查 `requireCleanRevocation()` 与 handle.stop()；只要 partial mount 有资源无法撤销，就升级为 `RUNTIME_MOUNT_CLEANUP_FAILED`，Core restore 立即 fail-closed，不继续把 takeover 发布为 owned；
- child stop 会先撤销 capability/discovery/binding，再 stop child handle，再 cancel + join scope；任一 close/stop/join timeout 都保留失败，`active[extensionId]` 不会被移除。
- child mount 的失败路径同样先建立可追踪 owner：即使 `entry.mount()` 尚未完整成功、或 handle 已返回但后置不变量失败，已发布的 capability/discovery/binding、可用 handle 与 coroutine scope 都先 pin 到 `active[extensionId]` 再走统一退休；清理失败时 owner 不丢失，禁止留下状态为 FAILED 但 capability 仍在线的幽灵资源。

这些约束的目的不是“尽量关掉”，而是防止一个没有真正退干净的旧 owner 被系统误判为已释放，从而让新 owner 重复注册 capability、重复启动 Ubuntu 或产生双业务状态。

## 状态与 fail-closed

Core status 新增/使用：

- `plugin_services_prepared`
- `ubuntu_control_ready`
- Kernel `resident_plugin_services_prepared`
- Kernel `resident_ubuntu_configured` / `resident_ubuntu_control_ready`
- Kernel plugin/child/capability runtime report
- `plugins_migrated` 只有在 `plugin_services_prepared=true` 时才为 true。

如果 enabled Ubuntu 没有 ACTIVE、关键 capability 缺失、child 明确 FAILED，或 BUSINESS restore 本身抛错，Core takeover 失败并保留诊断，不能发布 `owned`，也不能重新启动 Host 业务 runtime。

## 本阶段没有完成

- Host 目前仍只是 attach-only/UI shell；Core -> Host 的页面状态、事件、Compose/View presentation 与 Android component proxy 契约还需后续阶段接线。
- 本阶段没有把 Activity、window token、ActivityResult 或 UI-only page provider 放进 Core。
- `continuous_work` 仍为 false；CPU wake、网络冻结、LEV/freezer 与长锁屏持续执行还没有通过最终真机验收。
- Resident ON/OFF 总状态机与统一退出顺序仍未完成。
- 本阶段不修改 canonical System Environment Center / Ubuntu / Bridge / RDC / TriggerCMD / SentinelX 源码或协议，不修改版本号、Plugin Center 或 Current 注册表。

## 源码级完成口径

1. `owned` fence 之前，Core 已恢复普通 enabled HOT parent、活动 child point 下的 enabled children 与统一 Capability Registry。
2. canonical Ubuntu 若 enabled，必须 ACTIVE 且至少 `plugin.ubuntu.command` / `plugin.ubuntu.process` 在 Core registry 中存在。
3. Host UI_PROXY 不挂载第二套 Parent/Child business runtime。
4. `InProcessPageProvider` / `InProcessUiStateProvider`、screen、tile、child UI contribution 不成为 Core presentation；Core 仅持业务状态与 application/archive runtime Context。
5. capability canonical/alias 冲突原子失败，不覆盖旧 owner。
6. parent/child stop、scope join 或 owned resource revocation失败时，不宣称 clean retirement，不释放 owner identity；child partial mount 失败也必须先 pin owner 后清理。
7. Step 6/7 的单一门禁/Dispatcher/Bridge owner 以及 build25 两个真机修复继续保留。

以上仍是源码级结论；统一构建后必须通过真实 Ubuntu command/process/filesystem 调用、插件 capability 调用、Host 被杀、锁屏与 Resident OFF 资源释放测试才能进入真机验收。
