# build42：修复 Resident 接管时插件进程并发启动

## 故障证据

build41 / versionCode 114 的设备日志显示 Core 完成启动与依赖预检，发现 9 个已安装父插件，随后业务接管因 `Plugin runtime launch identity mismatch` 失败。插件进程日志同时记录一次 `Plugin runtime launch cancelled` 与另一次成功启动，随后因 Core 退出而结束。

`PluginRuntimeSupervisor.ensureRuntime()` 与 `RemoteChildExtensionRuntimeOwner.request()`、远程插件适配器均会调用 `PluginRuntimeController.probe()`。旧实现没有串行化生命周期事务，两个调用可同时观察到 stopped，各自写入共享的 launch.request 并启动进程。一方得到另一方启动的进程快照后，启动编号检查失败；失败路径又会删除共享启动凭据，进一步干扰另一方。

Core 退出后，Host 根据仍存在的接管门禁进入 UI_PROXY_BLOCKED。“稍后”仅关闭恢复对话框，不能解除接管门禁。Host 的插件展示和安装依赖 Core 提供的数据与命令通道，因此这时出现空界面与安装不可用。该链路不是插件卸载操作。

## 修复范围

- 为 PluginRuntimeController 增加同一个协程 Mutex，串行化完整 probe 与 stop 事务，覆盖状态判断、启动凭据写入、进程创建、就绪确认和清理。
- 等待中的 probe 在前一事务结束后重新检查状态，并使用已通过身份校验的同一工作进程。
- probe 内部需要停止不一致进程时调用已持锁的内部方法，避免重入非可重入 Mutex。
- 保留 stop 的 NonCancellable 语义，使停止等待及清理仍完整执行。
- 保留 Core / Host / 插件进程分工、权限交接、启动顺序、身份校验、门禁与显式恢复逻辑，不增加回退路径。

版本候选：`0.8.0.5-build42` / `versionCode 115`。沿用当前分支既有稳定包名与签名配置。

## 验证与交付

源码审查覆盖全部 probe/stop 调用入口、同锁启动与停止互斥、probe 内部停止的非重入路径及异常后的锁释放。使用 Git diff 检查改动范围与空白错误。

遵循仓库执行规则，不在手机或本地执行编译及测试命令。按本次用户要求推送当前分支并触发 GitHub Android 构建，触发后不监控构建。

新 APK 安装后的“开启 Resident → Host 重启 → 插件列表及安装通道可用”需要实机复测；静态检查和构建触发不等于实机验证通过。
