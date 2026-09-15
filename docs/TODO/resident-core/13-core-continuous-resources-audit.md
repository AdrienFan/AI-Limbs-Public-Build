# Step 11：Core CPU / 网络资源归属与源码总审计

状态：源码级完成候选，尚未编译、安装或真机验收。

本阶段不是宣称已经解决 Samsung LEV / freezer，而是把最终真机验收前最后一层架构边界补齐：持续 CPU / 网络资源跟随 Resident Core business session；Host 只保留 Android framework / UI shell 和交接窗口；状态层明确区分“进程活着、Wake token 存在、Android 网络可用、真实持续工作”四种不同事实；同时重新审计 owner、stop、death-recipient 与 fallback，确保源码层已经形成 Host 可死、Core 可工作、OFF 可释放的闭环。

## 1. Core session 成为持续资源 owner

新增 `ResidentCoreContinuousResources`，只在 `activate_business` 真正开始时获取资源，而不是 Core probe / skeleton 一启动就获取：

- owner 固定为当前 `resident_core`；
- owner 绑定当前 Core `session_id / pid / uid`；
- CPU 资源使用 `PowerManager.PARTIAL_WAKE_LOCK`，tag 为 `AI-Limbs:ResidentCore`；
- Core-owned plugin / Bridge / Host Network Primitive 的真实网络 I/O 在 BUSINESS role 下由 Core 进程执行；
- Core Context 另注册 `ConnectivityManager.registerDefaultNetworkCallback()` 作为 session-bound 网络状态观察资源，不把 callback 本身当成“网络保活锁”；
- 记录 active network、`NET_CAPABILITY_INTERNET`、`NET_CAPABILITY_VALIDATED`、metered 与 transport 类型；
- takeover cancel、可执行 finally 的 normal/error exit 与 Resident OFF 都显式释放资源；若 Core 被强杀，资源回收只能依赖进程死亡，由 OFF 明确记录 degraded，不能冒充 clean release。

资源 lease 不是独立于业务 owner 的常驻服务。只有准备进入 business takeover 时获取；probe / detached Core 不应长期持有 CPU / 网络资源。

## 2. Wake token 不等于 CPU 实际可执行

状态明确暴露：

- `process_alive`；
- `cpu_wake_token_held`；
- `cpu_wake_effective = null`；
- `cpu_wake_evidence = token_only_unverified`；
- `continuous_work = false`；
- `continuous_work_state = unverified`。

因此即使 `WakeLock.isHeld == true`，也不能据此判定 Samsung LEV / freezer 没有冻结该 UID，也不能判定 CPU 在长锁屏后仍能执行真实工作。

`PID / PPID=1 / oom_score_adj=-1000 / WakeLock.isHeld` 全部只属于诊断事实，不属于最终成功证据。

## 3. Android 网络状态不等于真实业务网络可用

Core 网络状态单独暴露：

- `network_callback_registered`；
- `network_available`；
- `network_internet_capability`；
- `network_validated`；
- `network_transports`；
- `network_real_io_verified = false`；
- `network_effective = null`。

`NetworkCallback` 只证明 Android framework 当时向 Core 报告了网络状态。它不能证明长锁屏 / freezer / LEV 后 socket、DNS、TLS、Bridge transport 或远程服务仍能真正工作。

最终网络成功证据仍必须来自正常 Bridge 链在锁屏后的真实远程调用。

## 4. Host WakeLock 退出 Resident steady-state ownership

`AIForegroundService` 不再 acquire Resident WakeLock：

- Resident continuous CPU ownership 只由 Core session 的 `AI-Limbs:ResidentCore` token 表达；
- Host 侧 `syncResidentCpuWakeLock()` 现在是 release-only，不再存在新的 acquire 路径；
- `AI-Limbs:ResidentHost` 相关字段 / state file 只保留旧版本或中断迁移残余的兼容释放与诊断用途；
- UI_PROXY 只保留 framework foreground shell / notification / Activity / UI / component proxy；
- Core 在 `activate_business` 时、Host runtime 退休之前取得自己的 session token，因此业务所有权接管不依赖 Host WakeLock。

OFF 仍会检查 Host 侧旧 token 残余已释放，但该检查只是 cleanup 事实，不是持续工作成功证据。

## 5. Guardian 不再以 Host WakeLock 作为健康条件

Guardian 协议从 3 升到 4，仅为内部 Resident protocol 兼容边界，不修改 App versionCode / versionName。

Guardian 现在读取 `host_shell.state`：

- `state`；
- Host `pid / uid`；
- `role = legacy_host | ui_proxy`；
- process identity。

Guardian 的职责只剩“Host framework shell 是否需要冷拉”。它不再把 Host WakeLock 是否 held 当成 Resident continuous-work 健康依据。

旧 protocol 3 Guardian 会被替换，避免升级后继续使用旧的 Host-WakeLock 健康语义。

## 6. Core ownership consistency 提升到每次 status

`ResidentCoreController.status()` 现在为 IPC 快照增加 `consistent`：

当 Core 声称 `business_attached=true` 时，必须同时满足：

- runtime owner = `resident_core`；
- Plugin Kernel / Bridge / plugin services / Ubuntu ready；
- continuous resource owner = 当前 Core session；
- Core Wake token held；
- Core network callback registered；
- Dispatcher owner = 当前 Core pid；
- Policy owner = 当前 Core pid；
- migration markers 一致；
- Core 仍不得宣称 `continuous_work=true`。

任何一致性失败都会令 `consistent=false`。Resident 总状态不能再因为 Core PID 仍存活而继续报告 `on`；Host Resolver 也会进入 UI_PROXY blocked / explicit recovery，而不是恢复 LEGACY_HOST business。

## 7. 资源 acquire / release fail-closed

持续资源采用 fail-closed cleanup：

- acquire 中途失败会立即尝试注销 network callback、释放 WakeLock；
- 若回滚释放失败，残余 handle 保留在对象中，不会被静默遗忘；
- release 失败时状态为 `release_failed`；
- 残余资源未释放时禁止重新 acquire 新 lease；
- takeover cancel / activation worker arm 失败如果不能 clean release，会关闭 Core server，让 finally 再次清理；
- Core shutdown report 记录 `continuous_resource_release_confirmed` 与完整资源释放诊断；
- shutdown report 读取上限从 4 KiB 放宽为 16 KiB，仅影响 shutdown report，不放宽 takeover fence 的 4 KiB 安全边界。

强制 kill Core 时，OFF 只能记录“资源回收依赖进程死亡”的 degraded 结果，不能冒充 clean release。

## 8. Owner / stop / death-recipient 总审计结论

### Permission backend

- Core handoff 开始后 backend Binder death 仍 fail-closed：Core 直接退出，不换另一个 backend 静默继续 business；
- normal OFF 仍要求 Core kernel 先退休，再 release permission backend；
- permission-server 的 Core lifetime death 路径仍只进入 recovery / return-to-host，不允许 UI_PROXY 原地恢复 business。

### Plugin Parent

- mount failure 先 revoke scope，并要求 clean revocation；
- stop timeout / failure 不从 `activeMounts` 移除 owner；
- Kernel retirement 任一 Parent / Child / notification cleanup 失败进入 `stop_failed`；
- `plugin_kernel` lease 仍保留到进程真实退出。

### Child Extension

- partial mount 已发布的 capability / discovery / binding / handle / scope 先 pin 到 `active`；
- stop 时 presentation ingress、capability、discovery、binding、handle、scope join 任一步失败都保留 owner；
- 只有全部 clean 才 `active.remove`。

### UI / Android Component Proxy

- Host attachment 继续绑定 `host_instance_id + host_generation`；
- 新 Host retire 旧 Host 并 cancel 旧 component claims；
- ActivityResult request 有 deadline；
- stale Host result / action 因 generation 不匹配被拒绝；
- Core 不持有 Activity / Window / ActivityResultLauncher。

### Business fallback

- takeover fence 存在时 Core 不可达，Host 只能 `UI_PROXY_BLOCKED`；
- Core business ownership 丢失时 Dispatcher client 返回 `fallback_allowed=false`；
- 不存在“Core 失败后 Host 静默恢复第二套 business runtime”的允许路径；
- 代码中的 shell `fallback` 仅用于显式 stop / kill cleanup，不是 business-owner fallback。

### Bridge scope

- Step 7 规则保持：transport `scope_id` 只是 transport-session metadata；
- Interaction Cycle / Policy / receipts 继续只有当前 business Core 一份权威状态。

## 9. 源码级闭环定义

完成本阶段后，源码目标为：

1. Host 可被杀死，Core business owner / Plugin / Child / Ubuntu / Bridge / Dispatcher 不因 Host UI shell 消失而转移回 Host。
2. Core business session 自己持有持续 CPU token 与网络观察资源；Host UI_PROXY 不持有稳态 Resident WakeLock。
3. Core owner / continuous-resource owner / Dispatcher / Policy 任一不一致时 fail closed，不允许“PID 还活着所以算 ON”。
4. Core normal stop 会显式退休 business runtime、释放 Core CPU / network lease、release permission backend，再退出进程。
5. release 失败会保持可观测残余 owner 或 degraded / failed 状态；不会静默忘掉资源。
6. Host / Core / Guardian 的 PID、Wake token、network callback 都只属于诊断维度，不会把 `continuous_work` 自动改为 true。

## 10. 最终真机验收仍未完成

本阶段没有证明 Samsung LEV / freezer 已经被突破，也没有证明 Android 其他厂商的长锁屏策略已经兼容。

最终成功标准必须至少包含：

- 统一 build26 安装后 Resident ON 成功；
- Host kill / restart 后 UI_PROXY 能重新 attach，而 Core business 不重建；
- 锁屏足够长时间，经历真实 suspend / freezer / LEV 场景；
- 使用正常 Bridge provider（RDC / TriggerCMD / SentinelX 或同 ABI provider）从外部进入；
- 真实调用 Core Dispatcher -> Plugin / Ubuntu，得到真实执行结果；
- 网络路径必须用真实 I/O 证明，而不是只看 `NET_CAPABILITY_VALIDATED`；
- Resident OFF 后确认 Core / Guardian / plugin_kernel lease / permission ownership / Wake token / network callback / business resources全部释放或以明确 degraded 诊断暴露。

只有上述真实调用通过，才允许把 `continuous_work` 从 `false / unverified` 改成成功状态。

## 11. 本阶段构建边界

- 版本保持 `0.8.0.5-build25 / code98`；
- 不修改 `CURRENT_COMPONENTS.json`；
- 不晋升 Current；
- 不运行 Gradle；
- 不发云构建；
- 不安装 APK / AILP；
- 不把源码级闭环描述成真机持续工作已通过。
