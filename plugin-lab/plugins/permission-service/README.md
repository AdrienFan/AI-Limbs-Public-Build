# AI Limbs 权限服务 v0.1.4

需要基座 build21 或更新版本，以及插件中心授予 host.privileged.runtime@1。插件位于工具箱，运行时不依赖 Ubuntu 或官方 Shizuku 应用。

## 使用

1. 安装支持权限服务的基座，再在插件中心安装并授权本插件。
2. 打开开发者选项的无线调试，选择使用配对码配对设备。
3. 建议分屏保持配对窗口打开，在插件内输入配对端口与配对码。
4. 配对成功后使用无线调试主页上的连接端口启动；两个端口不可混用。
5. 服务连接成功后显式选中 AI Limbs 后端。既有 Android 执行器通过 DEBUGGER 权限级别使用它；用户原有权限级别选择保持不变。
6. root 设备也可以明确选择 root 启动。停止和后端切换在权限服务页面中，诊断日志统一在日志中心查看。

设备重启后需要重新启动服务。无线配对仅支持 Android 11+，此版本原生库为 arm64-v8a。更换或损坏配对密钥时应明确重置后重新配对，不会静默生成替代身份。

## 架构

插件持有页面、ADB 配对密钥和客户端、服务端 APK 及诊断日志。服务端使用上游 Shizuku-API Service，通过独立 app_process 运行，只接受注册时核对的 Host UID。

基座静态 PrivilegeProvider 校验 shell/root 调用身份、启动 token、API 版本和真实服务 UID。Binder 保留在基座，插件只获得状态及受控管理操作。Shell 执行仍经过现有 Policy/Dispatcher 与插件身份检查。

host.privileged.runtime@1 提供 status / pair / prepare / stop / select。scope 必须获准，并且调用者必须是 plugin.system.permission_service。插件卸载或被阻止时，Host 在可信运行时 teardown 中撤销启动 token 并停止服务，不依赖插件已被撤销的调用权限。

服务每 10 秒与 Host 确认连接，允许有限的宿主进程重启宽限。token 撤销时退出，连续无法连接时退出。外部 Shizuku 的包名、Provider、进程和授权数据保持独立。

v0.1.4 不提供其他 Android 应用授权、Rish 或外部 UserService；现有外部 Shizuku/Sui 后端仍可通过明确选择使用。

## 构建与打包

独立云任务：android-build.yml，target=permission-service。

permission-service-plugin:assembleDebug 自动构建 permission-server 的 release APK 并作为 asset 打入插件；随后使用既有父插件 Ed25519 密钥签名生成 .ailp，校验签名及内容摘要。

当前修复沿用基座 build21，插件版本为 0.1.4。上游来源与修改见 THIRD_PARTY_NOTICES.md、vendor/UPSTREAM.json 和 vendor/shizuku-api/UPSTREAM.md。

## v0.1.4 Resident Host ownership 修复

- 常驻 BUSINESS 模式下，host.privileged.runtime@1 必须由 Android Host 执行，避免 Core 进程生成的 launch token 被 Host Provider 判定为失效。
- 插件在 Resident handoff 后重新挂载时立即读取真实权限后端状态，避免 Binder 已连接但页面短暂显示“未运行”。
- 保留 v0.1.2 的权限服务独立生命周期语义：Resident/Core 退出不主动停止权限服务 daemon。

## v0.1.2 Resident 生命周期解耦

- 权限服务异常退出时，不再要求 Resident Core 跟随退出。
- Resident/Core 生命周期结束时，只释放 ownership 并归还 Host，不再停止独立权限服务 daemon。
- 用户显式点击停止权限服务时，仍会正常停止服务。

## v0.1.1 启动修复

legacy ADB shell 使用临时 PTY。父 shell 执行后台命令后立即退出，后台进程可能在 setsid 前收到 SIGHUP。启动器现在先在父 shell 忽略 HUP，再启动 setsid 子进程；进入新会话后写入 AIL_SERVER_SESSION_READY 标记，并 exec 服务端。

服务端直接向重定向的标准错误文件描述符写入启动阶段和异常，避免仅使用 Android Log 导致 server.log 为空。诊断依然由插件转入日志中心，并脱敏启动令牌。

## 验证状态

旧插件的约 15 秒回连超时已在真机重现；同一个服务端 APK 的前台诊断可到达 Host 并正确拒绝无效令牌。当前修复完成源码和差异检查，未本地运行 Gradle；新版云编译与安装后的激活、断开 ADB 后存活、停止和重启行为待验证。不得把源码检查通过表述为新版真机验证通过。
