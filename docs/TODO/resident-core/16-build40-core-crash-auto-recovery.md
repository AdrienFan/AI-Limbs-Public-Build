# build40：Resident Core 崩溃自动恢复

目标：Resident 已完成业务接管后，`ail_resident_core` 异常死亡时，自动恢复而不破坏唯一业务 owner 与 no-silent-fallback。

## 恢复路径

1. Guardian 只在 `business_takeover.json` 为 `owned` 且记录 Core PID 已从 `/proc` 消失时向 Host 发送 `ACTION_RESIDENT_CORE_RECOVERY`；Guardian 不直接启动第二套 Core。
2. UI_PROXY Host 验证 desired Resident 仍为 ON、当前为 `UI_PROXY_BLOCKED`、旧 Core PID 已死亡、Core bootstrap lease 与 `plugin_kernel` lease 均已释放。
3. 验证后复用现有 `stopLocked()`，等待 permission backend 回到 Host、停止 Guardian、清理 stale policy/fence，并安排 Host 冷启动。
4. 自动恢复不修改 `enabled=true`。新 Host 因无 Core/fence 安全进入 `LEGACY_HOST`，正常恢复 Plugin Kernel 与插件；随后现有 `scheduleEnsureStarted()` 自动执行完整 Resident ON 事务。
5. 新 ON 仍走原有 prepare → policy freeze/export → Core arm → Host retirement → Core owner → Dispatcher/Bridge/plugins/Ubuntu restore → UI_PROXY attach，不新增旁路 ownership 协议。
6. 任一 owner-loss 证明失败时保留 `UI_PROXY_BLOCKED`；build32 的显式 `recoverBlockedHost()` 继续作为二级逃生并可让用户 Resident OFF 回普通 Host。

## 不变量

- Guardian 保持唯一 `guardian` lease，不拥有 Plugin Kernel、Dispatcher、Bridge 或插件业务。
- Host UI_PROXY 永不原地恢复业务 runtime。
- 自动恢复不删除 Plugin Store 或插件数据。
- `owned` fence 在 owner-loss 未被证明前不会被清除。
- 用户主动 OFF 先写 `enabled=false`，因此 Core 正常退出期间即使 Guardian 暂时看到旧 fence，Host 自动恢复也会 no-op。

版本候选：`0.8.0.5-build40` / `versionCode 113`。本轮只做源码迭代与静态检查，不触发云编译。
