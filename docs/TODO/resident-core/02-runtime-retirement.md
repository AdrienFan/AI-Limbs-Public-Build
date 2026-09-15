# 运行时退出与交接边界

本项从 ac9373b 继续。ResidentCoreWire 的 connect 必须位于 soTimeout 之前，AiLimbsResidentMain 的 guardian lease 必须在主循环前获取并在退出时关闭。

## 旧实现的问题

PluginPlatformKernel.start 捕获恢复错误后仍设置 started=true，两个并发 start 也可能同时进入恢复。shutdown 没有与 start 串行化，没有停止 ChildExtensionRuntime，且会吞掉插件和系统插件的停止错误。

PluginManager 能识别失败的停止，但总关闭流程没有向上报告。子扩展先从 active 删除，再忽略资源关闭与 stop 的失败。PluginMountScope 忽略资源关闭错误。普通插件取消协程 scope 后没有等待任务结束。这些行为不能作为交接运行权的成功凭据。

## 本项源码改动

内核的 start/shutdown 使用同一协程 Mutex 串行执行。诊断区分 initialized、starting、running、start_failed、stopping、stop_failed、stopped。异常恢复不再标成 running；失败的启动须先完成退出，才能再次启动。

关闭按监控任务、子扩展、普通插件、系统插件、通知资源的顺序执行。各层向上报告停止失败；子扩展保留失败的 handle，普通插件等待其 scope 内协程完成。资源撤销失败记录在挂载 scope 内，后续不能把曾失败的撤销重新算作成功。

Resident status 增加 host_kernel 诊断对象。该对象是状态观测，不授予运行权，也不证明 CPU 唤醒或锁屏可用。

plugin_kernel 文件锁仍保留到进程退出。即使 shutdown 返回成功，旧 VM 的注册表和引用仍存在，因此不能仅关闭文件锁就让 Core 接管。任何交接实现都必须先处理旧进程的在途请求和访问入口，确认旧所有者退出后，由新进程实际获取锁。

## 后续架构工作

1. 接入独立业务 Application 与主 Looper，将 Plugin Kernel、Bridge、Dispatcher、Interaction Cycle、插件服务及 Ubuntu 的控制权集中到 Core。Host 冷启动时必须只连接现有 Core，不能再次恢复插件。
2. 分离 Android 组件与插件界面。SystemUiPageV1、三个 System renderer 的 Compose 调用以及 InProcessPageProvider 返回的 View 都不能经普通对象序列化搬迁。ActivityResult、窗口 token、视图生命周期需要明确的界面代理契约；不得加载两套带业务副作用的插件来拼出界面。
3. 交接权限后端。PermissionController.close 本身只记录卸载，实际 exit 来自基座 PrivilegeRuntime.revokeOwner。应在确认接收端持有后端后，区分内核交接与用户停止。用户停止、撤销权限和退出 Resident 的控制必须始终生效。
4. 同时调整权限服务端的存续条件。当前 PermissionServer 每 10 秒回调 Host ContentProvider，连续失败后自行退出。仅把 App 端 Binder 引用复制给 Core，不能让该服务脱离 Host 的存续依赖。
5. 将持续 CPU 工作资源交由已授权且具备能力的独立后端持有，并与 Core 会话死亡绑定释放。App UID 下的新进程或新 WakeLock 不能视为通用冻结豁免。进程调度、CPU suspend 和网络必须分别记录并验证。
6. 接通 Resident ON/OFF 状态机、在途调用收尾、旧实例退出、新实例接管、Host 重连和明确的失败状态。不得在接管失败时暗中再启动 Host 业务运行时。

## 核查记录

已完成源码差异与调用链审阅；未执行编译、测试或真机切换。ac9373b 的两个修复文件保持原修复。本项不改变 installed/Current 的版本，也不将候选版本标记为完成或可验收。

[DONE] 本项退出生命周期的源码修改；整体迁移仍在进行。
