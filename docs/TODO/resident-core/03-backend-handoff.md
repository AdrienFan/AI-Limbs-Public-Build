# 权限后端交接协议

本项是 build26 候选架构的源码步骤。没有执行编译、测试、部署或真机切换；不改变 installed/Current。配套权限服务源码位于 AI-Limbs-Plugins 的 feat/resident-permission-lifetime 分支。现有发布包不具备本协议，必须与后续基座候选包一起构建验证。版本号在发布准备时统一更新。

## 已实现的边界

Core 以显式同包广播请求一次性 Binder offer。广播不携带权限服务 Binder 或启动 token。Host 通过现有 Unix socket 验证 Core 会话，再在取回 offer 的 Binder 事务中核对真实 UID、PID、launch ID 与 session ID，才交付权限服务连接。Receiver 不导出；撤销 launch.request 后拒绝交付。

独立 app_process 未向 AMS 注册普通 ApplicationThread，不能假设能够直接通过普通 Context 获取 ContentProvider。广播通过 IActivityManager 的已知签名调用，caller 为空，保留真实调用 UID；遇到未知 ABI 或拒绝直接报错。该启动路径仍需逐版本设备验证，不能仅凭源码视作已兼容。

Core 采用传入的权限连接时不写 Host 的 SharedPreferences，避免第二进程缓存把已撤销的 token 写回。普通 Host 接入逻辑保留原有持久化语义。

权限服务新增私有 Binder 协议 v1，transaction=0x41494c。describe 只读；prepare 将服务生命周期绑定到 Core 的 Binder token；claim 将已准备的会话标记为业务所有者；release 指定用户主动转回 Host 或终止服务。变更需要启动 token，且绑定实际调用 PID、会话和 lifetime Binder。服务继续限制调用 UID 为已验证的 App UID，公开 Shizuku API 保持原状。

准备或接管后，权限服务停止周期性 Host 回调；已在途的旧回调不能再据其结果终止 Core 的后端。Host Provider I/O 移到单独线程，服务主 Looper 可处理关闭。Core 的 Binder 死亡会终止后端，不会自动恢复 Host 业务。用户主动停止可明确转回 Host；普通权限插件卸载仍会撤销后端。

内核增加专用交接退出入口，携带绑定后端实例和 Core 会话的 retention permit。退出前以及卸载权限插件时重新核对 prepare 状态，只有该路径允许保留后端。普通 shutdown 不保留。plugin_kernel 文件锁仍由旧进程持有到进程退出。

Core 增加内部 prepare_handoff 命令，仍需 socket 的同 UID 和当前会话验证；它只准备后端，不迁移业务。状态分别报告连接、准备与业务所有权。停止时最多等待后端关闭 2 秒，再退出，并记录是否收到释放确认；Host 分别报告进程已退出与后端释放确认，不能混为一个成功标记。

## 尚未完成

1. Resident ON/OFF 尚未调用 prepareHandoff / shutdownForResidentHandoff；正常用户开关行为未迁移。
2. Step 4 已增加 Core owner-only Plugin Kernel 启动与 `claimRuntimeOwnership` 调用点；它尚未接入 Resident ON/OFF，且不会在本阶段恢复普通插件、子插件、Bridge、Dispatcher 或 Ubuntu。
3. Step 3/4 已补 Core 主 Looper、独立 Context 与“Host 退出后再获取 `plugin_kernel`”编排；仍需编译/真机验证，并在下一阶段让重启 Host 进入 UI_PROXY attach-only。
4. 插件 View/Compose、ActivityResult 与 Android 组件必须明确代理契约，不能跨进程传 Java 对象或恢复两个完整插件实例。
5. 本项没有新增 CPU 唤醒资源，没有解决 UID 冻结或网络策略；不得据此宣称所有品牌锁屏持续可用。

## 后续验收项（尚未执行）

覆盖未知服务协议、会话/PID 不匹配、重复准备、launch 撤销、Core 在 prepare/claim/release 各时点死亡、旧 Host Provider 调用迟到、服务断连、后台广播拒绝、关闭超时及用户 OFF。确认 socket connect/timeout 顺序和 Guardian 唯一实例锁不变。业务迁移后另验收长时间锁屏外部调用与资源释放。

## 平台依据

- [Android 16 ContentProviderHelper](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ContentProviderHelper.java)：普通 Provider 获取校验 caller 及 AMS 进程记录。
- [Android 16 BroadcastController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/BroadcastController.java)：广播入口保留实际调用 UID/PID，callerApp 可空。
- [Android 11 IActivityManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android11-release/core/java/android/app/IActivityManager.aidl) 与 [Android 12 IActivityManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android12-release/core/java/android/app/IActivityManager.aidl)：广播签名随平台演进。代码核对参数类型与数量后只发起一次调用。
