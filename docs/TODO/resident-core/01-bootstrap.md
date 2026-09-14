# build25：独立核心启动验证

状态：源码候选版；尚未完成 Android 云构建及真机验证，不能标记 DONE。

## 已实现范围

- ResidentCoreMain 从已安装 APK 的 app_process 入口启动，只构造包 Context，不执行 OperitApplication 或恢复插件
- LocalSocket 使用独立抽象地址；双方核对内核提供的 peer UID，客户端同时核对 PID
- 协议 v1 使用长度前缀 JSON，单帧上限 8 KiB，读超时 3 秒；关联 request_id 与 session_id
- 服务端仅接受 status 和需要当前 session 的 stop；不提供业务能力转发
- 启动用 launch.request 与随机 launch_id 关联；失败或取消会删除请求，延迟启动不能冒出新实例
- bootstrap 文件锁阻止重复进程；Plugin Kernel 在初始化前获取另一把进程生命周期锁，防止两进程同时持有运行时对象。shutdown 不代表对象全部销毁，因此此阶段不提前释放 Kernel 锁
- core_stop 在当前 session 接受停止后，核对租约释放和 PID 消失；不同 build 的兼容协议实例仍能停止
- Resident OFF 会取消验证启动、请求验证实例退出，并继续执行既有 Guardian 停止；错误通过 core_stop_error 和 last_error 返回
- 当前普通 Host 仍持有插件运行时和 WakeLock；没有建立新的有效 CPU 唤醒或网络豁免机制

## Host 接口

现有 host.resident.runtime@1 保留 status、set_enabled、start、stop，增加 core_status、core_probe、core_stop。三处操作清单与实际实现保持同步。调用者仍必须是 ai_limbs.system.plugin_center；core_probe 需要选中且连接的 AI Limbs 权限后端及可用 DEBUGGER Shell。

Plugin Center 开发调用形式：

```kotlin
facade.invokeHostPrimitive("host.resident.runtime@1", "core_probe")
facade.invokeHostPrimitive("host.resident.runtime@1", "core_status")
facade.invokeHostPrimitive("host.resident.runtime@1", "core_stop")
```

本阶段没有新增总控台按钮，普通 Resident ON 不会自动启动验证 Core。现有插件无需为了安装此候选版而重打包。

status 保留旧 mode 值以兼容已有读取者，新增 runtime_phase=guardian、runtime_owner=android_host、plugins_migrated=false、continuous_work_state=unverified 和 cpu_wake_effective=null。continuous_work 固定为 false，含义是当前没有持续工作已成立的证据；cpu_wake_lock=held 仅表示 Host 仍持有客户端 token。

Core 的状态额外报告 build_code、build_matches、launch_id、session_id、pid、uid、context_ready、resource_package、elapsed_ms、uptime_ms 和 suspend_ms。suspend_ms 是本实例存活期间 elapsedRealtime 与 uptimeMillis 增量之差；它反映整机睡眠时间，不用于推断是哪家厂商或哪个冻结器造成。

## 安装后的验证顺序

1. 用同签名 build25/code98 更新当前 build24，启动 Host，确认原有 Plugin Center、Bridge、Dispatcher 与 Ubuntu 可调用
2. 从 Plugin Center 的 Host 网关调用 core_probe；核对 build_code=98、build_matches=true、context_ready=true、plugins_migrated=false。独立 Context 使用框架反射，能否在当前系统运行必须由这一步确认
3. 再调用 core_probe，PID 与 session 必须不变；用同 UID app_process 启动 ResidentCoreMain status 也应得到当前现场状态
4. core_stop 后确认 PID 消失；重复停止应返回 stopped=true。重新 probe 应产生新 session
5. 启动验证实例后关闭 Resident，核对 Guardian 和验证 Core 都退出，Host WakeLock 释放；权限后端断开后，同 UID 停止流程仍应可用
6. 对启动取消、旧 build 残留、错误 session、重复启动、无响应 IPC 分别核对明确错误，不能报告已停止或自动重复启动
7. 锁屏采样只是记录边界；这一阶段的 Core 不持有唤醒锁，观察到 suspend 并不表示该验证入口失败，也绝不表示最终目标已经完成

下一阶段须先设计 View/状态/事件边界，再迁移实际业务，随后验证特权承载、冻结、CPU、网络与设备能力。
