# build32：Core 进程依赖预检与 Resident 失败恢复

## 真机故障来源

build31 在真机完成 Core 启动并进入业务接管后失败，`business_takeover.json` 记录：

```text
kotlin.UninitializedPropertyAccessException: lateinit property androidPermissionPreferences has not been initialized
```

故障后 takeover fence 保留为 `failed`，Host 按 no-silent-fallback 不变量进入 `UI_PROXY_BLOCKED`。Plugin Kernel 不恢复，因此已安装插件和 Plugin Center 在 UI 中全部不可见；插件数据本身未丢失。通过显式关闭 Resident desired-state 并清理已确认失去 Core owner 的 stale fence 后，build30 Host 能恢复全部插件。

## build32 修复

1. 新增 `OperitProcessContext`，为 Android Host 与独立 `app_process` Resident Core 提供进程内 application Context，而不在 Core 构造第二个 `OperitApplication`。
2. Resident Core 在进入 runtime skeleton ready 之前执行 `ResidentCoreDependencyPreflight`：初始化并实际读取 `androidPermissionPreferences`，验证 trust keyring 与 Plugin Store 可访问。预检失败时 Core 不进入可接管状态，Host 不退休。
3. 业务路径中原先直接依赖 `OperitApplication.instance` 的 Context 读取改为 `OperitProcessContext`，避免独立 Core 在插件恢复期间命中 Host-only Application singleton。
4. takeover 失败 fence 增加 `stage`，Core snapshot 增加 dependency-preflight 与 failure-stage 诊断。
5. Base 新增不依赖 Plugin Center / Plugin Kernel 的 `UI_PROXY_BLOCKED` 显式恢复入口。恢复动作保持 no-silent-fallback：先确认 Core/lease owner 已释放，再关闭 Resident desired-state、清理 stale policy/fence，并冷重启为 LEGACY_HOST；不修改插件 Store 或插件数据。
6. 保留 build31 的 Context identity、SELinux EACCES/EPERM liveness、connect-before-soTimeout，以及 `release_gate` 门禁语义修复。

## 版本与验证边界

候选版本：`0.8.0.5-build32` / `versionCode 105`。

本轮按用户要求不做本地 Gradle 编译；完成源码静态检查后提交并推送 GitHub，触发云端构建即结束，不监控构建进度。真机仍需按顺序验证：普通 Host 插件恢复 → Resident ON 接管 → UI_PROXY attach → Resident OFF 回 Host → 故障恢复 → 最后再做长时间锁屏 / Samsung LEV 验证。
