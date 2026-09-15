# Resident Core 独立业务进程骨架

状态：Step 3 源码骨架完成；未编译、未安装、未接管业务。

## 目标

把 `ResidentCoreMain` 从“只验证 Context/IPC 的 app_process”推进为真正拥有独立进程生命周期的 Core 骨架，同时严格保持本阶段不接管 Plugin Kernel、Bridge、Dispatcher、插件、Interaction Cycle 或持续工作责任。

本 Step 的 `running` 只表示 **Resident Core 自身运行时骨架已经真实启动**：独立 Context 成功、进程 main Looper 已实际执行启动 barrier、控制 IPC 已进入服务循环。它不表示业务接管，也不表示锁屏持续工作已经成立。

## 独立初始化边界

### Context Bootstrap

`ResidentCoreContextBootstrap` 只负责：

- 为 standalone `app_process` 准备 main Looper；
- 通过 framework `ActivityThread.systemMain()` 获取 system Context；
- 创建 AI Limbs package Context；
- 校验 UID 与资源包；
- 返回 `ContextWrapper`，其 `applicationContext` 指向自身。

它**绝不构造 `OperitApplication`**，因此不会触发 Host 的 Application 初始化、Plugin Kernel 恢复、插件 mount、Bridge/Dispatcher 初始化等副作用。

### Business Runtime Entry

`ResidentCoreBusinessRuntime` 是 Core 独立的业务运行时入口，但 Step 3 只初始化 runtime container，不挂载真实业务。它拥有自己的生命周期状态：

`created -> initialized -> starting -> running -> stopping -> stopped`

任何初始化、main-Looper barrier 或停止 barrier 异常会进入 `failed`，并记录 `last_error`。

## RUNNING 的真实门槛

`ResidentCoreMain` 创建 Context 和 LocalServerSocket 后**不会直接宣称 running**。控制线程调用 `ResidentCoreBusinessRuntime.start()`，该方法向进程 main Looper 投递启动 barrier，并等待 main Looper 实际执行。只有 barrier 执行完成后 phase 才能从 `starting` 进入 `running`。

因此以下情况都不能算 running：

- 只拿到了 package Context；
- 只绑定了 LocalServerSocket；
- 只创建了后台线程；
- main Looper 已创建但没有真正处理消息；
- startup barrier 超时或抛异常。

`ResidentCoreController.probe()` 现在还会再次校验：

- 顶层 `phase == running`；
- `core_runtime.runtime_skeleton_ready == true`；
- `core_runtime.main_looper_ready == true`；
- `business_attached == false`；
- `plugins_migrated == false`；
- `continuous_work == false`。

IPC 可达但这些条件不成立时，probe 不得把它当成成功启动。

## 主 Looper 与控制线程

进程主线程进入真实 `Looper.loop()`；socket accept / diagnostic IPC 在 `resident-core-control` 线程处理。这样未来需要 main-thread lifecycle 的 Core 业务组件有正式落点，同时 IPC 不阻塞主 Looper。

显式 `stop` 后，控制线程先让 runtime 经过 main-Looper stop barrier 到 `stopped`，再释放权限后端连接、写入 shutdown result 并退出进程。

## 失败语义

- Context bootstrap 失败：入口直接打印异常并以非零状态退出，不进入 runtime。
- runtime initialize/start 失败：phase 进入 `failed`，错误写入日志；shutdown result 记录最终 phase/runtime_error。
- main Looper 意外退出或抛异常：runtime 进入 `failed`，关闭控制 socket，进程失败退出。
- stop barrier 失败：不得伪报 stopped；phase 进入 `failed`，进程以失败状态退出。

不存在“启动了一半但 phase 仍写 running”的静默成功路径。

## 本 Step 明确未做

- 不调用 `PluginPlatformKernel.initialize(..., BUSINESS)`；
- 不获取 Plugin Kernel 业务 owner；
- 不恢复 Plugin Center、普通插件或子插件；
- 不迁移 Bridge、Dispatcher、Interaction Cycle/Policy；
- 不改变 Host 当前 `LEGACY_HOST` 启动行为；
- 不声明 Resident 锁屏持续工作已经完成。

状态字段继续明确：`runtime_owner=android_host`、`business_attached=false`、`plugins_migrated=false`、`continuous_work=false`。

## 下一步边界

下一阶段才允许设计 Core 如何在旧 Host 业务 owner 真实退出并释放 lease 后，以 `BUSINESS` 角色初始化 Plugin Kernel；在此之前不得提前调用业务初始化，也不得让 Host/Core 各恢复一套业务运行时。
